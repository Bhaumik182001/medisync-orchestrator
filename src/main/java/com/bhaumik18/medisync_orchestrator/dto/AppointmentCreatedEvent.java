package com.bhaumik18.medisync_orchestrator.dto;

public record AppointmentCreatedEvent(
	String patientEmail,
	Long timeSlotId
) {}
