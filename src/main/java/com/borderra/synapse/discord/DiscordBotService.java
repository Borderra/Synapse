package com.borderra.synapse.discord;

import com.borderra.synapse.api.DiscordBotApi;
import com.borderra.synapse.config.DiscordSettings;
import com.borderra.synapse.linking.LinkService;
import com.borderra.synapse.platform.PlatformScheduler;
import com.borderra.synapse.platform.SynapseTranslations;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DiscordBotService implements DiscordBotApi, AutoCloseable {
    private final DiscordSettings settings;
    private final LinkService linkService;
    private final SynapseTranslations translations;
    private final PlatformScheduler scheduler;
    private final Logger logger;
    private volatile JDA jda;

    public DiscordBotService(
            DiscordSettings settings,
            LinkService linkService,
            SynapseTranslations translations,
            PlatformScheduler scheduler,
            Logger logger
    ) {
        this.settings = settings;
        this.linkService = linkService;
        this.translations = translations;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    public void start() {
        if (settings.token() == null || settings.token().isBlank()) {
            logger.warning("Discord bot token is not configured. Discord features will remain disabled.");
            return;
        }

        try {
            DiscordDirectMessageListener listener = new DiscordDirectMessageListener(
                    linkService,
                    translations,
                    scheduler,
                    settings.replyToInvalidCodes(),
                    logger
            );
            this.jda = JDABuilder.createDefault(settings.token())
                    .enableIntents(EnumSet.of(GatewayIntent.DIRECT_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                    .addEventListeners(listener)
                    .build();
            logger.info("Discord bot login started.");
        } catch (RuntimeException e) {
            this.jda = null;
            logger.log(Level.SEVERE, "Failed to start Discord bot.", e);
        }
    }

    @Override
    public boolean isStarted() {
        return jda != null && jda.getStatus() != JDA.Status.SHUTDOWN && jda.getStatus() != JDA.Status.SHUTTING_DOWN;
    }

    @Override
    public CompletableFuture<Void> sendDirectMessage(String discordId, String message) {
        JDA current = jda;
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Discord bot is not started."));
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        current.retrieveUserById(discordId)
                .flatMap(user -> user.openPrivateChannel())
                .flatMap(channel -> channel.sendMessage(message))
                .queue(sent -> future.complete(null), future::completeExceptionally);
        return future;
    }

    @Override
    public CompletableFuture<Void> sendChannelMessage(String channelId, String message) {
        JDA current = jda;
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Discord bot is not started."));
        }

        TextChannel channel = current.getTextChannelById(channelId);
        if (channel == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Text channel is not available in the bot cache: " + channelId));
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        channel.sendMessage(message).queue(sent -> future.complete(null), future::completeExceptionally);
        return future;
    }

    @Override
    public Optional<Object> rawJda() {
        return Optional.ofNullable(jda);
    }

    @Override
    public void close() {
        JDA current = jda;
        jda = null;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitShutdown(Duration.ofSeconds(10))) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.shutdownNow();
        }
    }
}
