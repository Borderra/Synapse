package com.borderra.synapse.discord;

import com.borderra.synapse.api.LinkResult;
import com.borderra.synapse.api.LinkedAccount;
import com.borderra.synapse.linking.LinkService;
import com.borderra.synapse.platform.PlatformScheduler;
import com.borderra.synapse.platform.SynapseTranslations;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DiscordDirectMessageListener extends ListenerAdapter {
    private final LinkService linkService;
    private final SynapseTranslations translations;
    private final PlatformScheduler scheduler;
    private final boolean replyToInvalidCodes;
    private final Logger logger;

    public DiscordDirectMessageListener(
            LinkService linkService,
            SynapseTranslations translations,
            PlatformScheduler scheduler,
            boolean replyToInvalidCodes,
            Logger logger
    ) {
        this.linkService = linkService;
        this.translations = translations;
        this.scheduler = scheduler;
        this.replyToInvalidCodes = replyToInvalidCodes;
        this.logger = logger;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.isFromGuild()) {
            return;
        }

        String content = event.getMessage().getContentRaw().trim();
        if (!content.matches("\\d{4,12}")) {
            return;
        }

        User user = event.getAuthor();
        linkService.linkWithCode(content, user.getId(), user.getName())
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        logger.log(Level.WARNING, "Failed to link Discord DM code.", throwable);
                        if (replyToInvalidCodes) {
                            event.getChannel().sendMessage(translations.plain("link.invalid.discord")).queue();
                        }
                        return;
                    }
                    handleResult(event, result, user);
                });
    }

    private void handleResult(MessageReceivedEvent event, LinkResult result, User user) {
        if (!result.success()) {
            if (replyToInvalidCodes) {
                event.getChannel().sendMessage(translations.plain("link.invalid.discord")).queue();
            }
            return;
        }

        LinkedAccount account = result.account().orElseThrow();
        Map<String, String> replacements = replacements(account, user.getName());
        event.getChannel().sendMessage(translations.plain("link.success.discord", replacements)).queue();
        notifyMinecraft(account, user.getName());
    }

    private void notifyMinecraft(LinkedAccount account, String discordName) {
        scheduler.runGlobal(() -> {
            Player player = Bukkit.getPlayer(account.minecraftUuid());
            if (player == null || !player.isOnline()) {
                return;
            }
            scheduler.runForPlayer(player, () -> player.sendMessage(translations.prefixed(
                    player,
                    "link.success.minecraft",
                    replacements(account, discordName)
            )));
        });
    }

    private Map<String, String> replacements(LinkedAccount account, String discordName) {
        UUID uuid = account.minecraftUuid();
        return Map.of(
                "discord", discordName == null || discordName.isBlank() ? account.discordId() : discordName,
                "discord_id", account.discordId(),
                "minecraft", account.minecraftUsername(),
                "uuid", uuid.toString()
        );
    }
}
