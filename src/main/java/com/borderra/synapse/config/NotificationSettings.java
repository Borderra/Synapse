package com.borderra.synapse.config;

import java.time.Duration;

public record NotificationSettings(
        boolean enabled,
        Duration pollInterval,
        int batchSize,
        int maxAttempts
) {
}
