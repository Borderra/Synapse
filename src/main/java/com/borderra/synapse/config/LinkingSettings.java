package com.borderra.synapse.config;

import java.time.Duration;

public record LinkingSettings(
        int codeLength,
        Duration codeTtl,
        boolean allowRelink,
        String webUrl,
        Duration expiredCodeCleanupInterval
) {
}
