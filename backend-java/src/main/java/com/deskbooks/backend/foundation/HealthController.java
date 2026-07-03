package com.deskbooks.backend.foundation;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class HealthController {
    @GetMapping("/health")
    Map<String, Boolean> health() {
        return Map.of("ok", true);
    }
}
