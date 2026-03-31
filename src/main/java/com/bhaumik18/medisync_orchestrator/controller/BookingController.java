package com.bhaumik18.medisync_orchestrator.controller;

import com.bhaumik18.medisync_orchestrator.service.BookingOrchestratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orchestrator/bookings")
@RequiredArgsConstructor
public class BookingController {
	
	private final BookingOrchestratorService bookingOrchestratorService;
	
	@PostMapping("/{timeSlotId}")
	public ResponseEntity<String> bookSlot(
		@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
		@PathVariable Long timeSlotId
	) {
		String result = bookingOrchestratorService.orchestrateBooking(authHeader, timeSlotId);
		return ResponseEntity.ok(result);
	}
}
