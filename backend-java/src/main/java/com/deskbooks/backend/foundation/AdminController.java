package com.deskbooks.backend.foundation;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
class AdminController {
    private final ShutdownService shutdownService;

    AdminController(ShutdownService shutdownService) {
        this.shutdownService = shutdownService;
    }

    @PostMapping("/shutdown")
    ResponseEntity<Map<String, String>> shutdown() {
        if (!"1".equals(System.getenv("PFA_ALLOW_SHUTDOWN"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "shutdown is only enabled from ./run.sh"));
        }

        shutdownService.scheduleShutdown();
        return ResponseEntity.ok(Map.of("status", "stopping"));
    }
}
