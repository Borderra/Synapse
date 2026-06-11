package com.borderra.synapse.api;

import java.time.Instant;
import java.util.UUID;

public record LinkedAccount(
        String discordId,
        UUID minecraftUuid,
        String minecraftUsername,
        String discordUsername,
        Instant linkedAt,
        Instant updatedAt
) {
}
