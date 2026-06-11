package com.borderra.synapse.platform;

import com.borderra.borderralib.translation.TranslationLocales;
import com.borderra.borderralib.translation.TranslationService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class SynapseTranslations {
    private static final String PREFIX_KEY = "general.prefix";

    private final TranslationService service;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public SynapseTranslations(TranslationService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public TranslationService service() {
        return service;
    }

    public Component prefixed(CommandSender sender, String key) {
        return prefixed(sender, key, Map.of());
    }

    public Component prefixed(CommandSender sender, String key, Map<String, String> replacements) {
        Locale locale = locale(sender);
        return component(locale, PREFIX_KEY, Map.of()).append(component(locale, key, replacements));
    }

    public String plain(String key) {
        return plain(key, Map.of());
    }

    public String plain(String key, Map<String, String> replacements) {
        return miniMessage.stripTags(render(service.getActiveLocale(), key, replacements, false));
    }

    public String plain(Locale locale, String key, Map<String, String> replacements) {
        return miniMessage.stripTags(render(locale, key, replacements, false));
    }

    public Component component(CommandSender sender, String key, Map<String, String> replacements) {
        return component(locale(sender), key, replacements);
    }

    public Component component(Locale locale, String key, Map<String, String> replacements) {
        return miniMessage.deserialize(render(locale, key, replacements, true));
    }

    private Locale locale(CommandSender sender) {
        return TranslationLocales.fromSender(sender, service.getDefaultLocale());
    }

    private String render(Locale locale, String key, Map<String, String> replacements, boolean escapeMiniMessage) {
        String rendered = service.translate(key, locale);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (escapeMiniMessage) {
                value = miniMessage.escapeTags(value).replace("'", "&#39;");
            }
            rendered = rendered.replace("{" + entry.getKey() + "}", value);
        }
        return rendered;
    }
}
