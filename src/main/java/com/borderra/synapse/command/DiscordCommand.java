package com.borderra.synapse.command;

import com.borderra.synapse.api.LinkRequest;
import com.borderra.synapse.api.LinkedAccount;
import com.borderra.synapse.config.LinkingSettings;
import com.borderra.synapse.linking.LinkService;
import com.borderra.synapse.platform.PlatformScheduler;
import com.borderra.synapse.platform.SynapseTranslations;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DiscordCommand implements CommandExecutor, TabCompleter {
    private final LinkService linkService;
    private final LinkingSettings linkingSettings;
    private final SynapseTranslations translations;
    private final PlatformScheduler scheduler;
    private final Logger logger;

    public DiscordCommand(
            LinkService linkService,
            LinkingSettings linkingSettings,
            SynapseTranslations translations,
            PlatformScheduler scheduler,
            Logger logger
    ) {
        this.linkService = linkService;
        this.linkingSettings = linkingSettings;
        this.translations = translations;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("link")) {
            createLink(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("linked") || args[0].equalsIgnoreCase("lookup")) {
            lookup(sender, args);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("synapse.command.reload")) {
                sender.sendMessage(translations.prefixed(sender, "general.no-permission"));
                return true;
            }
            sender.sendMessage(translations.prefixed(sender, "general.restart-required"));
            return true;
        }

        sender.sendMessage(translations.prefixed(sender, "command.usage"));
        return true;
    }

    private void createLink(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(translations.prefixed(sender, "general.player-only"));
            return;
        }

        linkService.createLinkRequest(player.getUniqueId(), player.getName())
                .whenComplete((request, throwable) -> scheduler.runForPlayer(player, () -> {
                    if (throwable != null) {
                        logger.log(Level.WARNING, "Failed to create Discord link request.", unwrap(throwable));
                        player.sendMessage(translations.prefixed(player, "link.create-failed"));
                        return;
                    }
                    sendLinkMessages(player, request);
                }));
    }

    private void sendLinkMessages(Player player, LinkRequest request) {
        player.sendMessage(translations.prefixed(player, "link.code", Map.of(
                "code", request.code(),
                "minutes", String.valueOf(linkingSettings.codeTtl().toMinutes())
        )));

        if (request.webUrl() == null || request.webUrl().isBlank()) {
            return;
        }
        player.sendMessage(translations.prefixed(player, "link.click", Map.of(
                "url", request.webUrl(),
                "code", request.code(),
                "minutes", String.valueOf(linkingSettings.codeTtl().toMinutes())
        )));
    }

    private void lookup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("synapse.command.linked")) {
            sender.sendMessage(translations.prefixed(sender, "general.no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(translations.prefixed(sender, "lookup.usage"));
            return;
        }

        String query = args[1];
        if (query.matches("\\d{16,32}")) {
            linkService.findByDiscordId(query).whenComplete((result, throwable) -> sendLookupResult(sender, query, result, throwable));
            return;
        }

        UUID uuid = parseUuid(query).orElse(null);
        if (uuid != null) {
            linkService.findByMinecraft(uuid).whenComplete((result, throwable) -> sendLookupResult(sender, query, result, throwable));
            return;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(query);
        linkService.findByMinecraft(offlinePlayer.getUniqueId()).whenComplete((result, throwable) -> sendLookupResult(sender, query, result, throwable));
    }

    private void sendLookupResult(CommandSender sender, String query, Optional<LinkedAccount> result, Throwable throwable) {
        scheduler.runGlobal(() -> {
            if (throwable != null) {
                logger.log(Level.WARNING, "Failed to query linked account.", unwrap(throwable));
                sender.sendMessage(translations.prefixed(sender, "lookup.failed"));
                return;
            }

            if (result.isEmpty()) {
                sender.sendMessage(translations.prefixed(sender, "lookup.not-found", Map.of("query", query)));
                return;
            }

            LinkedAccount account = result.get();
            sender.sendMessage(translations.prefixed(sender, "lookup.found", Map.of(
                    "discord", account.discordUsername(),
                    "discord_id", account.discordId(),
                    "minecraft", account.minecraftUsername(),
                    "uuid", account.minecraftUuid().toString()
            )));
        });
    }

    private Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("link");
            if (sender.hasPermission("synapse.command.linked")) {
                options.add("linked");
            }
            return filter(options, args[0]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.startsWith(normalized))
                .toList();
    }
}
