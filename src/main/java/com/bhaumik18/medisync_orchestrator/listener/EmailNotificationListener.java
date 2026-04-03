package com.bhaumik18.medisync_orchestrator.listener;

import com.bhaumik18.medisync_orchestrator.config.RabbitMQConfig;
import com.bhaumik18.medisync_orchestrator.dto.AppointmentCreatedEvent;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class EmailNotificationListener {
	
	@RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
	public void handleAppointmentCreated(AppointmentCreatedEvent event) {
		System.out.println("\n=======================================================");
        System.out.println(">>> [WORKER THREAD] Woke up! Picked up message from RabbitMQ.");
        System.out.println(">>> [WORKER THREAD] Initiating connection to Email Server for: " + event.patientEmail());
        
        try {
        	Thread.sleep(3000);
        } catch (InterruptedException e) {
        	Thread.currentThread().interrupt();
        }
        
        System.out.println(">>> [WORKER THREAD] ✉️ SUCCESS! Email sent for Slot ID: " + event.timeSlotId());
        System.out.println("=======================================================\n");
	}
}
