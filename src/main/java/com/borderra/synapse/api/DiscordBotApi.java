package com.borderra.synapse.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface DiscordBotApi {
    boolean isStarted();

    CompletableFuture<Void> sendDirectMessage(String discordId, String message);

    CompletableFuture<Void> sendChannelMessage(String channelId, String message);

    /**
     * Returns the underlying JDA instance as an Object to keep Synapse's stable API independent
     * from JDA's binary API. Plugins that compile against JDA may cast this to net.dv8tion.jda.api.JDA.
     */
    Optional<Object> rawJda();
}
