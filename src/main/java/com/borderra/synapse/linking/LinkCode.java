package com.borderra.synapse.linking;

import java.time.Instant;
import java.util.UUID;

public record LinkCode(
        String code,
        UUID minecraftUuid,
        String minecraftUsername,
        Instant expiresAt
) {
}
