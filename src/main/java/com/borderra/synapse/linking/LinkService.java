package com.borderra.synapse.linking;

import com.borderra.synapse.api.LinkApi;
import com.borderra.synapse.api.LinkRequest;
import com.borderra.synapse.api.LinkResult;
import com.borderra.synapse.api.LinkedAccount;
import com.borderra.synapse.config.LinkingSettings;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class LinkService implements LinkApi {
    private static final int MAX_CODE_ATTEMPTS = 20;

    private final LinkRepository repository;
    private final LinkingSettings settings;
    private final SecureRandom random = new SecureRandom();

    public LinkService(LinkRepository repository, LinkingSettings settings) {
        this.repository = repository;
        this.settings = settings;
    }

    @Override
    public CompletableFuture<LinkRequest> createLinkRequest(UUID minecraftUuid, String minecraftUsername) {
        String normalizedName = normalizeMinecraftName(minecraftUsername);
        Instant expiresAt = Instant.now().plus(settings.codeTtl());
        return createCode(minecraftUuid, normalizedName, expiresAt, 0);
    }

    @Override
    public CompletableFuture<LinkResult> linkWithCode(String code, String discordId, String discordUsername) {
        String normalizedCode = normalizeCode(code);
        return repository.consumeCode(normalizedCode, discordId, discordUsername, settings.allowRelink());
    }

    @Override
    public CompletableFuture<Optional<LinkedAccount>> findByMinecraft(UUID minecraftUuid) {
        return repository.findByMinecraft(minecraftUuid);
    }

    @Override
    public CompletableFuture<Optional<LinkedAccount>> findByDiscordId(String discordId) {
        return repository.findByDiscordId(discordId);
    }

    public CompletableFuture<Integer> deleteExpiredCodes() {
        return repository.deleteExpiredCodes();
    }

    private CompletableFuture<LinkRequest> createCode(UUID minecraftUuid, String minecraftUsername, Instant expiresAt, int attempt) {
        if (attempt >= MAX_CODE_ATTEMPTS) {
            return CompletableFuture.failedFuture(new IllegalStateException("Could not allocate a unique link code after " + attempt + " attempts."));
        }

        String code = generateCode(settings.codeLength());
        return repository.replacePendingCode(minecraftUuid, minecraftUsername, code, expiresAt)
                .thenCompose(stored -> {
                    if (!stored) {
                        return createCode(minecraftUuid, minecraftUsername, expiresAt, attempt + 1);
                    }
                    return CompletableFuture.completedFuture(new LinkRequest(
                            code,
                            minecraftUuid,
                            minecraftUsername,
                            buildWebUrl(minecraftUuid, minecraftUsername, code),
                            expiresAt
                    ));
                });
    }

    private String generateCode(int length) {
        int upperBound = (int) Math.pow(10, length);
        int value = random.nextInt(upperBound);
        return String.format(Locale.ROOT, "%0" + length + "d", value);
    }

    private String buildWebUrl(UUID uuid, String username, String code) {
        String template = settings.webUrl();
        if (template == null || template.isBlank()) {
            return "";
        }
        return template
                .replace("{uuid}", encode(uuid.toString()))
                .replace("{code}", encode(code))
                .replace("{mc_code}", encode(code))
                .replace("{username}", encode(username))
                .replace("{mc_username}", encode(username));
    }

    private String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        return code.trim();
    }

    private String normalizeMinecraftName(String minecraftUsername) {
        if (minecraftUsername == null || minecraftUsername.isBlank()) {
            return "unknown";
        }
        return minecraftUsername.trim();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
