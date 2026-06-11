package com.borderra.synapse.notification;

import java.util.UUID;

public record QueuedNotification(
        long id,
        String discordId,
        UUID minecraftUuid,
        String minecraftUsername,
        String discordUsername,
        String message,
        int attempts
) {
}
