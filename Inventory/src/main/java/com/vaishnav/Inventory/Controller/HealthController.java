package com.vaishnav.Inventory.Controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@CrossOrigin(originPatterns = "*")
public class HealthController {

    @GetMapping({"/health", "/api/health"})
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "vaishnav-inventory",
                "timestamp", Instant.now().toString()
        );
    }
}
