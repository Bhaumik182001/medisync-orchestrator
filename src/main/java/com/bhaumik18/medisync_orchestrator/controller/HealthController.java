package com.bhaumik18.medisync_orchestrator.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/v1/orchestrator/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Orchestrator is UP");
    }
}