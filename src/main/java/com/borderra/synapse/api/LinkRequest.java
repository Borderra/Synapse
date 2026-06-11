package com.borderra.synapse.api;

import java.time.Instant;
import java.util.UUID;

public record LinkRequest(
        String code,
        UUID minecraftUuid,
        String minecraftUsername,
        String webUrl,
        Instant expiresAt
) {
}
