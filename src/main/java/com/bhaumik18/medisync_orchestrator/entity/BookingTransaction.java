package com.bhaumik18.medisync_orchestrator.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "booking_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingTransaction {
	
	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	private Long timeSlotId;
	private String status;
	private String failureReason;
}
