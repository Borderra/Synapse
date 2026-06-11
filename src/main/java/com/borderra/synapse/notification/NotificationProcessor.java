package com.borderra.synapse.notification;

import com.borderra.synapse.api.DiscordBotApi;
import com.borderra.synapse.config.NotificationSettings;
import com.borderra.synapse.linking.LinkRepository;
import com.borderra.synapse.platform.PlatformScheduler;
import com.borderra.synapse.platform.SynapseTranslations;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class NotificationProcessor implements AutoCloseable {
    private final LinkRepository repository;
    private final DiscordBotApi discordBot;
    private final NotificationSettings settings;
    private final SynapseTranslations translations;
    private final PlatformScheduler scheduler;
    private final Logger logger;
    private ScheduledTask task;

    public NotificationProcessor(
            LinkRepository repository,
            DiscordBotApi discordBot,
            NotificationSettings settings,
            SynapseTranslations translations,
            PlatformScheduler scheduler,
            Logger logger
    ) {
        this.repository = repository;
        this.discordBot = discordBot;
        this.settings = settings;
        this.translations = translations;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    public void start() {
        if (!settings.enabled()) {
            return;
        }
        task = scheduler.runAsyncRepeating(this::poll, settings.pollInterval(), settings.pollInterval());
    }

    private void poll() {
        repository.pendingNotifications(settings.batchSize(), settings.maxAttempts())
                .thenAccept(notifications -> notifications.forEach(this::process))
                .exceptionally(throwable -> {
                    logger.log(Level.WARNING, "Failed to poll Synapse notifications.", throwable);
                    return null;
                });
    }

    private void process(QueuedNotification notification) {
        notifyMinecraft(notification);
        String discordMessage = notification.message() == null || notification.message().isBlank()
                ? defaultDiscordMessage(notification)
                : notification.message();

        discordBot.sendDirectMessage(notification.discordId(), discordMessage)
                .thenCompose(ignored -> repository.deleteNotification(notification.id()))
                .exceptionally(throwable -> {
                    repository.markNotificationFailed(notification.id(), rootMessage(throwable));
                    return null;
                });
    }

    private void notifyMinecraft(QueuedNotification notification) {
        scheduler.runGlobal(() -> {
            Player player = Bukkit.getPlayer(notification.minecraftUuid());
            if (player == null || !player.isOnline()) {
                return;
            }
            scheduler.runForPlayer(player, () -> player.sendMessage(translations.prefixed(
                    player,
                    "link.success.minecraft",
                    Map.of(
                            "discord", notification.discordUsername(),
                            "discord_id", notification.discordId(),
                            "minecraft", notification.minecraftUsername(),
                            "uuid", notification.minecraftUuid().toString()
                    )
            )));
        });
    }

    private String defaultDiscordMessage(QueuedNotification notification) {
        return translations.plain("link.success.discord", Map.of(
                "discord", notification.discordUsername(),
                "discord_id", notification.discordId(),
                "minecraft", notification.minecraftUsername(),
                "uuid", notification.minecraftUuid().toString()
        ));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @Override
    public void close() {
        scheduler.cancel(task);
        task = null;
    }
}
