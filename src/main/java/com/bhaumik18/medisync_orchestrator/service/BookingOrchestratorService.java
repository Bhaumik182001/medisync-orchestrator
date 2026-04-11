package com.bhaumik18.medisync_orchestrator.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    // Inject the URL from application.yml
    @Value("${services.core.url}")
    private String coreServiceBaseUrl;

    
    public String orchestrateBooking(String authHeader, Long timeSlotId, String patientEmail) {
        String mdcTraceId = MDC.get("traceId");
        log.info(">>> [SERVICE START] Inside orchestrateBooking. MDC Trace ID: {}", mdcTraceId != null ? mdcTraceId : "NULL");
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
                
               
                coreServiceClient.put()
                     .uri(coreServiceBaseUrl + "/api/v1/schedules/slots/" + timeSlotId + "/book")
                     .header(HttpHeaders.AUTHORIZATION, authHeader) 
                     .retrieve()
                     .toBodilessEntity();
            };
            
            Runnable retriableCall = Retry.decorateRunnable(retry, networkCall);
            circuitBreaker.executeRunnable(retriableCall);
            
            saga.setStatus("SLOT_LOCKED");
            transactionRepository.save(saga);
            
            log.info(">>> Contacting Payment Gateway...");
            boolean paymentSuccess = new Random().nextInt(100) < 90;
            
            if (paymentSuccess) {
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                log.info(">>> Payment APPROVED.");
            } else {
                throw new RuntimeException("Payment Gateway Declined the card");
            }
            
            saga.setStatus("CONFIRMED");
            transactionRepository.save(saga);
            
            AppointmentCreatedEvent event = new AppointmentCreatedEvent(patientEmail, timeSlotId);
            
            rabbitTemplate.convertAndSend(
                    com.bhaumik18.medisync_orchestrator.config.RabbitMQConfig.EXCHANGE_NAME,
                    com.bhaumik18.medisync_orchestrator.config.RabbitMQConfig.ROUTING_KEY,
                    event
            );
            
            log.info(">>> [MAIN THREAD] Fired async email event to RabbitMQ for {}!", patientEmail);
            return "SUCCESS: Appointment completely finalized!";
            
        } catch(CallNotPermittedException e) {
            log.warn(">>> 🛑 CIRCUIT BREAKER TRIPPED! Core Service is unresponsive. <<<");
            return "SERVICE DEGRADED: The booking system is currently experiencing high traffic.";
            
        } catch(Exception e) {
            log.error(">>> CRITICAL FAILURE: {} Triggering Compensating Transaction... <<<", e.getMessage());
            
            if("SLOT_LOCKED".equals(saga.getStatus()) || (e.getMessage() != null && e.getMessage().contains("Payment"))) {
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
            .uri(coreServiceBaseUrl + "/api/v1/appointments/cancel/" + timeSlotId)
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
                        .uri(coreServiceBaseUrl + "/api/v1/appointments/schedule/" + providerId)
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
                return "SERVICE DEGRADED: Live schedule is unavailable.";
            }
        } catch (Exception redisException) {
            return "SERVICE COMPLETELY DEGRADED.";
        }
    }

    public void orchestrateCancellation(String authHeader, Long timeSlotId, String patientEmail) {
        log.info(">>> Orchestrator: Canceling appointment for Slot {} by {} <<<", timeSlotId, patientEmail);
        
      
        coreServiceClient.put()
             .uri(coreServiceBaseUrl + "/api/v1/schedules/slots/" + timeSlotId + "/cancel")
             .header(HttpHeaders.AUTHORIZATION, authHeader)
             .retrieve()
             .toBodilessEntity();

        String cancelPayload = String.format("CANCELED_APPOINTMENT: Slot ID %d for %s", timeSlotId, patientEmail);
        
        rabbitTemplate.convertAndSend(
                com.bhaumik18.medisync_orchestrator.config.RabbitMQConfig.EXCHANGE_NAME,
                com.bhaumik18.medisync_orchestrator.config.RabbitMQConfig.ROUTING_KEY,
                cancelPayload
        );
        
        log.info(">>> Cancellation complete. RabbitMQ notified for {}. <<<", patientEmail);
    }
}