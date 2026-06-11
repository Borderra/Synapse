# Synapse

Synapse is a Paper plugin under `com.borderra.synapse` that links Minecraft accounts to Discord accounts through either Discord bot DMs or a configurable Discord OAuth web flow.

## Build

```bash
mvn package
```

The shaded plugin jar is written to `target/synapse-1.0.0-SNAPSHOT.jar`.

If Maven cannot resolve `com.borderra:borderralib:1.0.0`, install BorderraLib locally first:

```bash
cd /path/to/BorderraLib
mvn install
```

## Configuration

`config.yml` intentionally ships with placeholders instead of secrets:

```yaml
database:
  password: "${SYNAPSE_DB_PASSWORD}"
discord:
  token: "${SYNAPSE_DISCORD_TOKEN}"
```

If a value is exactly `${ENV_NAME}`, Synapse resolves it from the process environment. You can also put a literal value in the generated server config if that is how the deployment is managed.

Synapse creates tables using `database.table_prefix`, defaulting to:

- `synapse_link_codes`
- `synapse_account_links`
- `synapse_link_history`
- `synapse_notifications`

## Translations

Synapse uses BorderraLib's plugin-local `TranslationService`, shaded into the plugin at build time. On startup it creates:

- `plugins/Synapse/translations.yml`
- `plugins/Synapse/translations/en-US.yml`
- `plugins/Synapse/translations/fr-FR.yml`

`translations.yml` controls the default and active locale:

```yaml
default-locale: en-US
active-locale: en-US
fallback-to-default: true
locale-folder: translations
```

Player-facing Minecraft messages use the player's client locale when a matching locale file exists, with fallback to `default-locale`. Discord messages use `active-locale`.

To add a language, add a locale file such as `translations/es-ES.yml`. Synapse uses MiniMessage formatting in translation values and named placeholders such as `{code}`, `{url}`, `{minecraft}`, and `{discord}`.

## Commands

- `/discord link` creates a temporary link code and optional OAuth link for the player.
- `/discord linked <playerName|minecraftUuid|discordId>` queries an existing link and requires `synapse.command.linked`.

## API Usage

Other plugins should depend or softdepend on `Synapse`, then retrieve the API through Bukkit services:

```java
import com.borderra.synapse.api.SynapseApi;
import com.borderra.synapse.api.SynapseProvider;

SynapseProvider.get().ifPresent(api -> {
    api.links().findByDiscordId("123456789012345678").thenAccept(link -> {
        link.ifPresent(account -> {
            // account.minecraftUuid(), account.minecraftUsername(), etc.
        });
    });

    api.discord().sendChannelMessage("123456789012345678", "Message from another plugin");
});
```

`api.discord().rawJda()` returns the underlying JDA instance as `Object` for advanced integrations that compile against JDA, while keeping the stable Synapse API independent from JDA binary compatibility.

## OAuth Example

`web/link.php` is an example Discord OAuth bridge. It uses environment variables instead of hard-coded secrets:

- `DISCORD_CLIENT_ID`
- `DISCORD_CLIENT_SECRET`
- `DISCORD_REDIRECT_URI`
- `SYNAPSE_DB_DSN`
- `SYNAPSE_DB_USER`
- `SYNAPSE_DB_PASSWORD`
- `SYNAPSE_TABLE_PREFIX`, optional
- `SYNAPSE_ALLOW_RELINK`, optional

The plugin-generated web URL can use either modern placeholders or old-style aliases:

- `{uuid}`
- `{code}` or `{mc_code}`
- `{username}` or `{mc_username}`
