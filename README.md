# MediSync Orchestrator Service
[![MediSync Orchestrator CI](https://github.com/Bhaumik182001/medisync-orchestrator/actions/workflows/ci-pipeline.yml/badge.svg)](https://github.com/Bhaumik182001/medisync-orchestrator/actions)

The **Orchestrator Service** acts as the central API Gateway and Distributed Transaction Manager for the MediSync ecosystem. It decouples client requests from backend microservices, ensuring cross-service data consistency using the **Saga Orchestration Pattern**. It is highly fault-tolerant, utilizing Circuit Breakers to degrade gracefully during network outages.

## 🛠️ Tech Stack
* **Language:** Java 21
* **Framework:** Spring Boot 4.0.5
* **Resilience:** Resilience4j (Circuit Breaker, Retry)
* **Message Broker:** RabbitMQ (Spring AMQP)
* **Database (Saga State):** PostgreSQL 15
* **Cache (Fallback):** Redis
* **Client:** Spring `RestClient`

## 🏗️ Architectural Patterns & Triumphs

* **Saga Pattern (Distributed Transactions):** Manages the complex multi-step booking process across services. It maintains a localized `BookingTransaction` state machine (`PENDING` ➔ `SLOT_LOCKED` ➔ `CONFIRMED`).
* **Automated Compensating Transactions:** If a downstream process (like the simulated Payment Gateway) fails, the Orchestrator automatically fires a compensating `DELETE` request to the Core Service to unlock the `TimeSlot`, guaranteeing zero orphaned bookings.
* **Circuit Breakers & Retries:** Implements `Resilience4j` around network calls to the Core Service. If the Core Service fails (or is injected with latency via Chaos Monkey), the Circuit Breaker trips to `OPEN`, preventing cascading system failures.
* **Graceful Degradation (Redis Fallback):** If the Core Service goes down while a patient is trying to view a doctor's schedule, the Orchestrator intercepts the `CallNotPermittedException` and successfully serves stale schedule data directly from the Redis Cache.
* **Event-Driven Handoff:** Fully decouples the Notification layer. Once a booking is confirmed, it fires an `AppointmentCreatedEvent` to a RabbitMQ Topic Exchange and immediately returns an HTTP response to the client, preventing thread blocking.

## 📡 Key API Endpoints

All endpoints are prefixed with `/api/v1/orchestrator`.

| HTTP Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/bookings/{timeSlotId}` | Initiates the Saga transaction to lock the slot, process payment, and notify RabbitMQ. |
| `POST` | `/bookings/{timeSlotId}/cancel` | Triggers a cancellation in the Core service and fires a cancellation event. |
| `GET` | `/schedules/{providerId}` | Fetches schedules via Core Service (with automatic Redis fallback on failure). |

## 🚀 Getting Started

### Prerequisites
* **Java 21** installed locally.
* **Docker & Docker Compose** (PostgreSQL, Redis, RabbitMQ).
* **Core & Identity Services** must be running for full end-to-end functionality.

### 1. Start the Infrastructure
The Orchestrator requires PostgreSQL (to log Saga states), Redis, and RabbitMQ:
```bash
docker-compose up -d postgres redis rabbitmq
```

### 2. Run the Application
You can run the application using the Maven wrapper:
```bash
./mvnw spring-boot:run
```
*The service will start on `http://localhost:8083`.*

### Environment Variables
The application relies on the following configurations mapped in `application.yaml`:

```yaml
server:
  port: 8083
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/medisync_orchestrator
    username: postgres
    password: rootpassword
services:
  identity:
    url: http://localhost:8081
  core:
    url: http://localhost:8082
resilience4j:
  circuitbreaker:
    instances:
      coreService:
        sliding-window-size: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
```