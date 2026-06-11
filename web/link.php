<?php
/**
 * Synapse Discord OAuth link example.
 *
 * Required environment variables:
 * - DISCORD_CLIENT_ID
 * - DISCORD_CLIENT_SECRET
 * - DISCORD_REDIRECT_URI, for example https://example.com/link.php
 * - SYNAPSE_DB_DSN, for example mysql:host=127.0.0.1;dbname=synapse;charset=utf8mb4
 * - SYNAPSE_DB_USER
 * - SYNAPSE_DB_PASSWORD
 *
 * Optional environment variables:
 * - SYNAPSE_TABLE_PREFIX, defaults to synapse_
 * - SYNAPSE_ALLOW_RELINK, defaults to true
 */

declare(strict_types=1);

session_start();

function env_required(string $name): string
{
    $value = getenv($name);
    if ($value === false || trim($value) === '') {
        fail(500, "Missing required environment variable: {$name}");
    }
    return trim($value);
}

function fail(int $status, string $message): never
{
    http_response_code($status);
    echo htmlspecialchars($message, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
    exit;
}

function table_prefix(): string
{
    $prefix = getenv('SYNAPSE_TABLE_PREFIX') ?: 'synapse_';
    if (!preg_match('/^[a-z0-9_]+$/', $prefix)) {
        fail(500, 'Invalid SYNAPSE_TABLE_PREFIX.');
    }
    return $prefix;
}

function now_millis(): int
{
    return (int) floor(microtime(true) * 1000);
}

function oauth_redirect(string $uuid, string $code, string $username): never
{
    $_SESSION['synapse_uuid'] = $uuid;
    $_SESSION['synapse_code'] = $code;
    $_SESSION['synapse_username'] = $username;
    $_SESSION['synapse_state'] = bin2hex(random_bytes(32));

    $params = [
        'client_id' => env_required('DISCORD_CLIENT_ID'),
        'redirect_uri' => env_required('DISCORD_REDIRECT_URI'),
        'response_type' => 'code',
        'scope' => 'identify',
        'state' => $_SESSION['synapse_state'],
    ];

    header('Location: https://discord.com/api/oauth2/authorize?' . http_build_query($params));
    exit;
}

function post_form(string $url, array $fields): array
{
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => http_build_query($fields),
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER => ['Content-Type: application/x-www-form-urlencoded'],
        CURLOPT_TIMEOUT => 10,
    ]);
    $result = curl_exec($ch);
    $error = curl_error($ch);
    curl_close($ch);

    if ($result === false) {
        fail(502, 'Discord token request failed: ' . $error);
    }

    $decoded = json_decode($result, true);
    if (!is_array($decoded)) {
        fail(502, 'Discord token response was not valid JSON.');
    }
    return $decoded;
}

function get_json(string $url, array $headers): array
{
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_HTTPHEADER => $headers,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 10,
    ]);
    $result = curl_exec($ch);
    $error = curl_error($ch);
    curl_close($ch);

    if ($result === false) {
        fail(502, 'Discord user request failed: ' . $error);
    }

    $decoded = json_decode($result, true);
    if (!is_array($decoded)) {
        fail(502, 'Discord user response was not valid JSON.');
    }
    return $decoded;
}

function pdo(): PDO
{
    return new PDO(env_required('SYNAPSE_DB_DSN'), env_required('SYNAPSE_DB_USER'), env_required('SYNAPSE_DB_PASSWORD'), [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
}

function complete_link(string $minecraftUuid, string $minecraftCode, string $discordId, string $discordUsername): string
{
    $prefix = table_prefix();
    $allowRelink = filter_var(getenv('SYNAPSE_ALLOW_RELINK') ?: 'true', FILTER_VALIDATE_BOOLEAN);
    $now = now_millis();
    $db = pdo();

    try {
        $db->beginTransaction();

        $codeQuery = "SELECT code, minecraft_uuid, minecraft_username FROM {$prefix}link_codes "
            . "WHERE code = ? AND minecraft_uuid = ? AND expires_at > ? FOR UPDATE";
        $stmt = $db->prepare($codeQuery);
        $stmt->execute([$minecraftCode, $minecraftUuid, $now]);
        $pending = $stmt->fetch();
        if (!$pending) {
            $db->rollBack();
            fail(400, 'Invalid or expired Minecraft link code.');
        }
        $storedMinecraftUsername = (string) $pending['minecraft_username'];

        if (!$allowRelink) {
            $stmt = $db->prepare("SELECT id FROM {$prefix}account_links WHERE discord_id = ? OR minecraft_uuid = ? LIMIT 1");
            $stmt->execute([$discordId, $minecraftUuid]);
            if ($stmt->fetch()) {
                $db->rollBack();
                fail(409, 'That Discord or Minecraft account is already linked.');
            }
        }

        $archive = "INSERT INTO {$prefix}link_history "
            . "(discord_id, minecraft_uuid, minecraft_username, discord_username, linked_at, replaced_at, replacement_reason) "
            . "SELECT discord_id, minecraft_uuid, minecraft_username, discord_username, linked_at, ?, ? "
            . "FROM {$prefix}account_links WHERE discord_id = ? OR minecraft_uuid = ?";
        $stmt = $db->prepare($archive);
        $stmt->execute([$now, 'oauth', $discordId, $minecraftUuid]);

        $stmt = $db->prepare("DELETE FROM {$prefix}account_links WHERE discord_id = ? OR minecraft_uuid = ?");
        $stmt->execute([$discordId, $minecraftUuid]);

        $stmt = $db->prepare("DELETE FROM {$prefix}link_codes WHERE code = ?");
        $stmt->execute([$minecraftCode]);

        $stmt = $db->prepare("INSERT INTO {$prefix}account_links "
            . "(discord_id, minecraft_uuid, minecraft_username, discord_username, linked_at, updated_at) "
            . "VALUES (?, ?, ?, ?, ?, ?)");
        $stmt->execute([$discordId, $minecraftUuid, $storedMinecraftUsername, $discordUsername, $now, $now]);

        $stmt = $db->prepare("INSERT INTO {$prefix}notifications "
            . "(discord_id, minecraft_uuid, minecraft_username, discord_username, message, created_at) "
            . "VALUES (?, ?, ?, ?, ?, ?)");
        $stmt->execute([$discordId, $minecraftUuid, $storedMinecraftUsername, $discordUsername, null, $now]);

        $db->commit();
        return $storedMinecraftUsername;
    } catch (Throwable $e) {
        if ($db->inTransaction()) {
            $db->rollBack();
        }
        fail(500, 'Failed to complete link.');
    }
}

if (!isset($_GET['state'])) {
    $uuid = $_GET['uuid'] ?? '';
    $code = $_GET['code'] ?? ($_GET['mc_code'] ?? '');
    $username = $_GET['username'] ?? ($_GET['mc_username'] ?? '');

    if (!preg_match('/^[0-9a-fA-F-]{36}$/', $uuid)) {
        fail(400, 'Missing or invalid uuid parameter.');
    }
    if (!preg_match('/^\d{4,12}$/', $code)) {
        fail(400, 'Missing or invalid code parameter.');
    }
    if ($username === '' || strlen($username) > 32) {
        fail(400, 'Missing or invalid username parameter.');
    }

    oauth_redirect($uuid, $code, $username);
}

if (!isset($_GET['code'], $_GET['state'], $_SESSION['synapse_state'])) {
    fail(400, 'Missing OAuth callback state.');
}
if (!hash_equals($_SESSION['synapse_state'], (string) $_GET['state'])) {
    fail(400, 'Invalid OAuth state.');
}

$tokenData = post_form('https://discord.com/api/oauth2/token', [
    'client_id' => env_required('DISCORD_CLIENT_ID'),
    'client_secret' => env_required('DISCORD_CLIENT_SECRET'),
    'grant_type' => 'authorization_code',
    'code' => (string) $_GET['code'],
    'redirect_uri' => env_required('DISCORD_REDIRECT_URI'),
]);
if (!isset($tokenData['access_token'])) {
    fail(502, 'Discord did not return an access token.');
}

$userData = get_json('https://discord.com/api/users/@me', [
    'Authorization: Bearer ' . $tokenData['access_token'],
]);
if (!isset($userData['id'])) {
    fail(502, 'Discord did not return a user id.');
}

$discordId = (string) $userData['id'];
$discordUsername = (string) ($userData['global_name'] ?? $userData['username'] ?? $discordId);
$linkedMinecraftUsername = complete_link(
    (string) $_SESSION['synapse_uuid'],
    (string) $_SESSION['synapse_code'],
    $discordId,
    $discordUsername
);

unset($_SESSION['synapse_uuid'], $_SESSION['synapse_code'], $_SESSION['synapse_username'], $_SESSION['synapse_state']);

echo 'Successfully linked Minecraft account '
    . htmlspecialchars($linkedMinecraftUsername, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8')
    . ' with Discord account '
    . htmlspecialchars($discordUsername, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8')
    . '.';
