package com.borderra.synapse.config;

public record DiscordSettings(
        String token,
        String clientId,
        boolean dmLinkingEnabled,
        boolean replyToInvalidCodes
) {
}
