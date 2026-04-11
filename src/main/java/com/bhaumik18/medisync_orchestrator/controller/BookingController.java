package com.bhaumik18.medisync_orchestrator.controller;

import com.bhaumik18.medisync_orchestrator.service.BookingOrchestratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Base64;

@RestController
@RequestMapping("/api/v1/orchestrator")
@RequiredArgsConstructor
public class BookingController {
	
    private final BookingOrchestratorService bookingOrchestratorService;
	
    // 1. The Booking Endpoint
    @PostMapping("/bookings/{timeSlotId}")
    public ResponseEntity<String> bookSlot(
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
        @PathVariable Long timeSlotId
    ) {
        // Extract the email using the helper below
        String patientEmail = extractEmailFromJwt(authHeader);
        
        String result = bookingOrchestratorService.orchestrateBooking(authHeader, timeSlotId, patientEmail);
        return ResponseEntity.ok(result);
    }

    // 2. The Cancellation Endpoint
    @PostMapping("/bookings/{timeSlotId}/cancel")
    public ResponseEntity<String> cancelSlot(
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
        @PathVariable Long timeSlotId
    ) {
        // Extract the email using the helper below
        String patientEmail = extractEmailFromJwt(authHeader);
        
        bookingOrchestratorService.orchestrateCancellation(authHeader, timeSlotId, patientEmail);
        return ResponseEntity.ok("Appointment canceled successfully.");
    }

    // 3. The Resilient Schedule Endpoint
    @GetMapping("/schedules/{providerId}")
    public ResponseEntity<String> getProviderSchedule(
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
        @PathVariable Long providerId
    ) {
        String result = bookingOrchestratorService.fetchProviderSchedule(providerId, authHeader);
        if (result.startsWith("SERVICE DEGRADED")) {
            return ResponseEntity.status(503).body(result);
        }
        return ResponseEntity.ok(result);
    }

    // --- HELPER METHOD TO DECODE JWT ---
 // --- HELPER METHOD TO DECODE JWT ---
 // --- HELPER METHOD TO DECODE JWT (ZERO DEPENDENCIES) ---
    private String extractEmailFromJwt(String authHeader) {
        try {
            // 1. Remove "Bearer " prefix
            String token = authHeader.substring(7);
            
            // 2. JWTs have 3 parts separated by dots. The payload is the 2nd part.
            String[] chunks = token.split("\\.");
            
            // 3. Decode the Base64 payload into a raw JSON string
            String payload = new String(java.util.Base64.getUrlDecoder().decode(chunks[1]));
            
            // 4. Use pure Java Regex to find the "sub" field (e.g., "sub":"patient@email.com")
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]+)\"").matcher(payload);
            
            if (matcher.find()) {
                return matcher.group(1); // Returns exactly the email string
            }
            
            return "unknown-patient@medisync.com";
        } catch (Exception e) {
            System.err.println("Failed to decode JWT: " + e.getMessage());
            return "unknown-patient@medisync.com"; // Safe fallback
        }
    }
}