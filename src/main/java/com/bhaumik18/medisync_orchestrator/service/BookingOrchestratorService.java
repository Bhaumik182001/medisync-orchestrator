package com.bhaumik18.medisync_orchestrator.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.slf4j.MDC;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingOrchestratorService {
    
    private final org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
    private final RestClient coreServiceClient;
    private final BookingTransactionRepository transactionRepository;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final StringRedisTemplate redisTemplate;

    
    public String orchestrateBooking(String authHeader, Long timeSlotId, String patientEmail) {
    	String mdcTraceId = MDC.get("traceId");
        System.out.println("\n-------------------------------------------------------");
        log.info(">>> [SERVICE START] Inside orchestrateBooking. MDC Trace ID: {}", mdcTraceId != null ? mdcTraceId : "NULL");
        System.out.println("-------------------------------------------------------\n");
    	log.info(">>> Orchestrator intercepting request. Forwarding to Core Service... <<<");
        
        BookingTransaction saga = BookingTransaction.builder()
                .timeSlotId(timeSlotId)
                .status("PENDING")
                .build();
        
        
        
        saga = transactionRepository.save(saga);
        
        try {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("coreService");
            Retry retry = retryRegistry.retry("coreService");
            
            Runnable networkCall = () -> {
                String threadTraceId = MDC.get("traceId");
                log.info(">>> [INSIDE NETWORK THREAD] Preparing RestClient. MDC Trace ID: {}", threadTraceId != null ? threadTraceId : "NULL");
                
                // THE FIX: Use PUT, point to the correct Core endpoint, and we don't need a body
                coreServiceClient.put()
                     .uri("http://localhost:8082/api/v1/schedules/slots/" + timeSlotId + "/book")
                     .header(HttpHeaders.AUTHORIZATION, authHeader) // Forwards the JWT!
                     .retrieve()
                     .toBodilessEntity();
            };
            
            Runnable retriableCall = Retry.decorateRunnable(retry, networkCall);
            circuitBreaker.executeRunnable(retriableCall);
            
            saga.setStatus("SLOT_LOCKED");
            transactionRepository.save(saga);
            
            log.info(">>> Contacting Payment Gateway...");
            boolean paymentSuccess = new Random().nextBoolean();
            
            if (paymentSuccess) {
                try { Thread.sleep(4000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                log.info(">>> Payment APPROVED.");
            } else {
                try { Thread.sleep(8000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                throw new RuntimeException("Payment Gateway Declined the card after 8-second fraud check");
            }
            
            saga.setStatus("CONFIRMED");
            transactionRepository.save(saga);
            
            AppointmentCreatedEvent event = new AppointmentCreatedEvent("patient@email.com", timeSlotId);
            
            rabbitTemplate.convertAndSend(
                    com.bhaumik18.medisync_orchestrator.config.RabbitMQConfig.EXCHANGE_NAME,
                    com.bhaumik18.medisync_orchestrator.config.RabbitMQConfig.ROUTING_KEY,
                    event
            );
            
            log.info(">>> [MAIN THREAD] Fired async email event to RabbitMQ for {}! Returning response.", patientEmail);
            return "SUCCESS: Appointment completely finalized!";
            
        } catch(CallNotPermittedException e) {
            log.warn(">>> 🛑 CIRCUIT BREAKER TRIPPED! Core Service is unresponsive. <<<");
            return "SERVICE DEGRADED: The booking system is currently experiencing high traffic.";
            
        } catch(Exception e) {
            log.error(">>> CRITICAL FAILURE: {} Triggering Compensating Transaction... <<<", e.getMessage());
            
            if("SLOT_LOCKED".equals(saga.getStatus()) || e.getMessage().contains("Payment")) {
                fireCompensatingTransaction(authHeader, timeSlotId);
                saga.setStatus("COMPENSATED");
                saga.setFailureReason("Payment failed, slot released: " + e.getMessage());
                transactionRepository.save(saga);
                return "FAILED: Payment declined. The time slot has been safely released.";
            }
            
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
        
        log.info(">>> COMPENSATING TRANSACTION COMPLETE: Slot {} is free again. <<<", timeSlotId);
    }

    public String fetchProviderSchedule(Long providerId, String authHeader) {
        log.info(">>> Orchestrator: Fetching schedule for Provider {} <<<", providerId);
        
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("coreService");
        Retry retry = retryRegistry.retry("coreService");

        try {
            Supplier<String> networkCall = () -> {
                return coreServiceClient.get()
                        .uri("/api/v1/appointments/schedule/" + providerId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader)
                        .retrieve()
                        .body(String.class);
            };

            Supplier<String> retriableCall = Retry.decorateSupplier(retry, networkCall);
            return circuitBreaker.executeSupplier(retriableCall);
            
        } catch (Exception e) {
            return fallbackScheduleFromRedis(providerId, e);
        }
    }

    private String fallbackScheduleFromRedis(Long providerId, Exception e) {
        log.warn(">>> 🛑 CIRCUIT BROKEN / TIMEOUT: Bypassing Core Service. <<<");
        log.info(">>> 🟢 FALLBACK TRIGGERED: Attempting to fetch from Redis Cache... <<<");
        
        String redisKey = "provider_schedule:" + providerId;
        try {
            String cachedSchedule = redisTemplate.opsForValue().get(redisKey);
            if (cachedSchedule != null && !cachedSchedule.isEmpty()) {
                log.info(">>> ✅ CACHE HIT: Returning stale data to user.");
                return cachedSchedule; 
            } else {
                log.warn(">>> ❌ CACHE MISS: No data available in Redis.");
                return "SERVICE DEGRADED: Live schedule is unavailable and no cached data exists.";
            }
        } catch (Exception redisException) {
            log.error(">>> 💥 CRITICAL: Redis cache is also unreachable!");
            return "SERVICE COMPLETELY DEGRADED.";
        }
    }

    public void orchestrateCancellation(String authHeader, Long timeSlotId, String patientEmail) {
        log.info(">>> Orchestrator: Canceling appointment for Slot {} by {} <<<", timeSlotId, patientEmail);
        
        // 1. Tell Core Service to free the slot
        // (Core Service will safely extract the email from the forwarded authHeader to verify ownership)
        coreServiceClient.put()
             .uri("http://localhost:8082/api/v1/schedules/slots/" + timeSlotId + "/cancel")
             .header(HttpHeaders.AUTHORIZATION, authHeader)
             .retrieve()
             .toBodilessEntity();

        // 2. Fire Async Notification to RabbitMQ
        String cancelPayload = String.format("CANCELED_APPOINTMENT: Slot ID %d for %s", timeSlotId, patientEmail);
        
        rabbitTemplate.convertAndSend(
                com.bhaumik18.medisync_orchestrator.config.RabbitMQConfig.EXCHANGE_NAME,
                com.bhaumik18.medisync_orchestrator.config.RabbitMQConfig.ROUTING_KEY,
                cancelPayload
        );
        
        log.info(">>> Cancellation complete. RabbitMQ notified for {}. <<<", patientEmail);
    }
}