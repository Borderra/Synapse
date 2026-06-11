package com.borderra.synapse.config;

public record SynapseSettings(
        DatabaseSettings database,
        DiscordSettings discord,
        LinkingSettings linking,
        NotificationSettings notifications
) {
}
