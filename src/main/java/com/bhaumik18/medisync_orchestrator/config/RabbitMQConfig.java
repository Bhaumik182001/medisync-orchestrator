package com.bhaumik18.medisync_orchestrator.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "appointment_exchange";
    public static final String QUEUE_NAME = "email_notification_queue";
    public static final String ROUTING_KEY = "appointment.created";

    // 1. Create the Queue (The Mailbox)
    @Bean
    public Queue emailQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    // 2. Create the Exchange (The Post Office)
    @Bean
    public TopicExchange appointmentExchange() {
        return new TopicExchange(EXCHANGE_NAME);
    }

    // 3. Bind them together
    @Bean
    public Binding binding(Queue emailQueue, TopicExchange appointmentExchange) {
        return BindingBuilder.bind(emailQueue).to(appointmentExchange).with(ROUTING_KEY);
    }

    // 4. Modern Spring Boot 4.x / AMQP 4.x JSON Converter
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}