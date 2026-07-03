package com.deskbooks.backend.foundation;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

@Service
class ShutdownService {
    private final ConfigurableApplicationContext context;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "deskbooks-shutdown");
        thread.setDaemon(false);
        return thread;
    });

    ShutdownService(ConfigurableApplicationContext context) {
        this.context = context;
    }

    void scheduleShutdown() {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }

        executor.schedule(() -> {
            int exitCode = SpringApplication.exit(context, () -> 0);
            System.exit(exitCode);
        }, 200, TimeUnit.MILLISECONDS);
    }
}
