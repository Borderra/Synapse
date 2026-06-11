package com.borderra.synapse.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SynapseConfig {
    private static final Pattern ENV_PATTERN = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)}$");

    private SynapseConfig() {
    }

    public static SynapseSettings load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        FileConfiguration config = plugin.getConfig();

        DatabaseSettings database = new DatabaseSettings(
                config.getString("database.host", "127.0.0.1"),
                config.getInt("database.port", 3306),
                config.getString("database.name", "synapse"),
                config.getString("database.username", "synapse"),
                resolveSecret(config.getString("database.password", "")),
                config.getBoolean("database.use_ssl", false),
                sanitizePrefix(config.getString("database.table_prefix", "synapse_")),
                Math.max(1, config.getInt("database.pool_size", 5)),
                Math.max(1000L, config.getLong("database.connection_timeout_ms", 10000L))
        );

        DiscordSettings discord = new DiscordSettings(
                resolveSecret(config.getString("discord.token", "")),
                config.getString("discord.client_id", ""),
                config.getBoolean("discord.enable_dm_linking", true),
                config.getBoolean("discord.reply_to_invalid_codes", true)
        );

        LinkingSettings linking = new LinkingSettings(
                clamp(config.getInt("linking.code_length", 6), 4, 12),
                Duration.ofMinutes(Math.max(1, config.getLong("linking.code_ttl_minutes", 15))),
                config.getBoolean("linking.allow_relink", true),
                config.getString("linking.web_url", ""),
                Duration.ofMinutes(Math.max(1, config.getLong("linking.cleanup_expired_codes_minutes", 30)))
        );

        NotificationSettings notifications = new NotificationSettings(
                config.getBoolean("notifications.enabled", true),
                Duration.ofSeconds(Math.max(5, config.getLong("notifications.poll_interval_seconds", 15))),
                Math.max(1, config.getInt("notifications.batch_size", 25)),
                Math.max(1, config.getInt("notifications.max_attempts", 3))
        );

        return new SynapseSettings(database, discord, linking, notifications);
    }

    private static String resolveSecret(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Matcher matcher = ENV_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            return value;
        }
        return System.getenv().getOrDefault(matcher.group(1), "");
    }

    private static String sanitizePrefix(String value) {
        String prefix = value == null ? "synapse_" : value.toLowerCase(Locale.ROOT).trim();
        if (!prefix.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("database.table_prefix may only contain lowercase letters, numbers, and underscores.");
        }
        return prefix;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
