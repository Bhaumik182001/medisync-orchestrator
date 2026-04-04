package com.bhaumik18.medisync_orchestrator.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.bhaumik18.medisync_orchestrator.dto.AppointmentCreatedEvent;
import com.bhaumik18.medisync_orchestrator.entity.BookingTransaction;
import com.bhaumik18.medisync_orchestrator.repository.BookingTransactionRepository;

import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

@Service
@RequiredArgsConstructor
public class BookingOrchestratorService {
    
    private final org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
    private final RestClient coreServiceClient;
    private final BookingTransactionRepository transactionRepository;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    
    // 1. Injected Redis for the Fallback Cache
    private final StringRedisTemplate redisTemplate;
    
    // --- THE WRITE PATTERN (SAGA + CIRCUIT BREAKER + RETRY) ---
    public String orchestrateBooking(String authHeader, Long timeSlotId) {
        
        System.out.println(">>> Orchestrator intercepting request. Forwarding to Core Service... <<<");
        
        BookingTransaction saga = BookingTransaction.builder()
                .timeSlotId(timeSlotId)
                .status("PENDING")
                .build();
        
        saga = transactionRepository.save(saga);
        
        try {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("coreService");
            Retry retry = retryRegistry.retry("coreService");
            
            // 1. Create the raw network call
            Runnable networkCall = () -> {
                 Map<String, Long> requestBody = Map.of("timeSlotId", timeSlotId);
                 coreServiceClient.post()
                     .uri("/api/v1/appointments/book")
                     .header(HttpHeaders.AUTHORIZATION, authHeader)
                     .contentType(MediaType.APPLICATION_JSON)
                     .body(requestBody)
                     .retrieve()
                     .toBodilessEntity();
            };
            
            // 2. Wrap it in the Retry logic
            Runnable retriableCall = Retry.decorateRunnable(retry, networkCall);
            
            // 3. Wrap the Retriable call in the Circuit Breaker logic and execute
            circuitBreaker.executeRunnable(retriableCall);
            
            saga.setStatus("SLOT_LOCKED");
            transactionRepository.save(saga);
            
            // 2. Simulate Payment with Variable Latency (4s Success / 8s Fraud Check)
            System.out.println(">>> Contacting Payment Gateway...");
            boolean paymentSuccess = new Random().nextBoolean();
            
            if (paymentSuccess) {
                try { Thread.sleep(4000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                System.out.println(">>> Payment APPROVED.");
            } else {
                try { Thread.sleep(8000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                throw new RuntimeException("Payment Gateway Declined the card after 8-second fraud check");
            }
            
            // 3. Confirm Transaction
            saga.setStatus("CONFIRMED");
            transactionRepository.save(saga);
            
            // 4. Fire Asynchronous Event
            AppointmentCreatedEvent event = new AppointmentCreatedEvent("patient@email.com", timeSlotId);
            
            rabbitTemplate.convertAndSend(
                    com.bhaumik18.medisync_orchestrator.config.RabbitMQConfig.EXCHANGE_NAME,
                    com.bhaumik18.medisync_orchestrator.config.RabbitMQConfig.ROUTING_KEY,
                    event
            );
            
            System.out.println(">>> [MAIN THREAD] Fired async email event to RabbitMQ! Returning response to user.");
            
            return "SUCCESS: Appointment completely finalized!";
            
        } catch(CallNotPermittedException e) {
            System.out.println(">>> 🛑 CIRCUIT BREAKER TRIPPED! Core Service is unresponsive. <<<");
            return "SERVICE DEGRADED: The booking system is currently experiencing high traffic. Please try again in a few moments.";
            
        } catch(Exception e) {
            System.out.println(">>> CRITICAL FAILURE: " + e.getMessage() + " Triggering Compensating Transaction... <<<");
            
            // Check if we need to compensate BEFORE we overwrite the status to FAILED
            if("SLOT_LOCKED".equals(saga.getStatus()) || e.getMessage().contains("Payment")) {
                fireCompensatingTransaction(authHeader, timeSlotId);
                saga.setStatus("COMPENSATED");
                saga.setFailureReason("Payment failed, slot released: " + e.getMessage());
                transactionRepository.save(saga);
                return "FAILED: Payment declined. The time slot has been safely released.";
            }
            
            // If it failed before locking the slot (e.g. Core Service is down)
            saga.setStatus("FAILED");
            saga.setFailureReason(e.getMessage());
            transactionRepository.save(saga);
            
            return "FAILED: Could not book appointment. " + e.getMessage();
        }
    }
    
    private void fireCompensatingTransaction(String authHeader, Long timeSlotId) {
        coreServiceClient.delete()
            .uri("/api/v1/appointments/cancel/" + timeSlotId)
            .header(HttpHeaders.AUTHORIZATION, authHeader)
            .retrieve()
            .toBodilessEntity();
        
        System.out.println(">>> COMPENSATING TRANSACTION COMPLETE: Slot " + timeSlotId + " is free again. <<<");
    }

    // --- THE READ FALLBACK PATTERN (WITH RETRY) ---
    public String fetchProviderSchedule(Long providerId, String authHeader) {
        System.out.println(">>> Orchestrator: Fetching schedule for Provider " + providerId + " <<<");
        
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("coreService");
        Retry retry = retryRegistry.retry("coreService");

        try {
            // 1. Define the network call returning a String
            Supplier<String> networkCall = () -> {
                return coreServiceClient.get()
                        .uri("/api/v1/appointments/schedule/" + providerId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader)
                        .retrieve()
                        .body(String.class);
            };

            // 2. Wrap it in Retry
            Supplier<String> retriableCall = Retry.decorateSupplier(retry, networkCall);

            // 3. Execute through the Circuit Breaker
            return circuitBreaker.executeSupplier(retriableCall);
            
        } catch (CallNotPermittedException e) {
            // The breaker is OPEN. Do not fail! Rescue the user with cached data.
            return fallbackScheduleFromRedis(providerId, e);
        } catch (Exception e) {
            // The breaker is CLOSED, but it failed/timed out after retrying.
            return fallbackScheduleFromRedis(providerId, e);
        }
    }

    private String fallbackScheduleFromRedis(Long providerId, Exception e) {
        System.out.println(">>> 🛑 CIRCUIT BROKEN / TIMEOUT: Bypassing Core Service. <<<");
        System.out.println(">>> 🟢 FALLBACK TRIGGERED: Attempting to fetch from Redis Cache... <<<");
        
        String redisKey = "provider_schedule:" + providerId;
        
        try {
            String cachedSchedule = redisTemplate.opsForValue().get(redisKey);
            
            if (cachedSchedule != null && !cachedSchedule.isEmpty()) {
                System.out.println(">>> ✅ CACHE HIT: Returning stale data to user.");
                return cachedSchedule; 
            } else {
                System.out.println(">>> ❌ CACHE MISS: No data available in Redis.");
                return "SERVICE DEGRADED: Live schedule is unavailable and no cached data exists. Please try again later.";
            }
        } catch (Exception redisException) {
            System.out.println(">>> 💥 CRITICAL: Redis cache is also unreachable!");
            return "SERVICE COMPLETELY DEGRADED: Both primary and fallback systems are currently offline.";
        }
    }
}