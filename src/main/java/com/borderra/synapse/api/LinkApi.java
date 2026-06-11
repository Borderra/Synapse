package com.borderra.synapse.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface LinkApi {
    CompletableFuture<LinkRequest> createLinkRequest(UUID minecraftUuid, String minecraftUsername);

    CompletableFuture<LinkResult> linkWithCode(String code, String discordId, String discordUsername);

    CompletableFuture<Optional<LinkedAccount>> findByMinecraft(UUID minecraftUuid);

    CompletableFuture<Optional<LinkedAccount>> findByDiscordId(String discordId);
}
