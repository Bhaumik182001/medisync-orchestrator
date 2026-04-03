package com.bhaumik18.medisync_orchestrator.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.bhaumik18.medisync_orchestrator.dto.AppointmentCreatedEvent;
import com.bhaumik18.medisync_orchestrator.entity.BookingTransaction;
import com.bhaumik18.medisync_orchestrator.repository.BookingTransactionRepository;

import java.util.Map;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class BookingOrchestratorService {
    
    private final org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
    private final RestClient coreServiceClient;
    private final BookingTransactionRepository transactionRepository;
    
    public String orchestrateBooking(String authHeader, Long timeSlotId) {
        
        System.out.println(">>> Orchestrator intercepting request. Forwarding to Core Service... <<<");
        
        BookingTransaction saga = BookingTransaction.builder()
                .timeSlotId(timeSlotId)
                .status("PENDING")
                .build();
        
        saga = transactionRepository.save(saga);
        
        try {
            // 1. Lock the slot synchronously
            Map<String, Long> requestBody = Map.of("timeSlotId", timeSlotId);
            coreServiceClient.post()
                    .uri("/api/v1/appointments/book")
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toBodilessEntity();
            
            saga.setStatus("SLOT_LOCKED");
            transactionRepository.save(saga);
            
            // 2. Simulate Payment
            boolean paymentSuccess = new Random().nextBoolean();
            if(!paymentSuccess) {
                throw new RuntimeException("Payment Gateway Declined the card");
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
}