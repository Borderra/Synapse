package com.borderra.synapse.linking;

import com.borderra.synapse.api.LinkFailureReason;
import com.borderra.synapse.api.LinkResult;
import com.borderra.synapse.api.LinkedAccount;
import com.borderra.synapse.database.TableNames;
import com.borderra.synapse.notification.QueuedNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class LinkRepository {
    private final DataSource dataSource;
    private final TableNames tables;
    private final Executor executor;

    public LinkRepository(DataSource dataSource, TableNames tables, Executor executor) {
        this.dataSource = dataSource;
        this.tables = tables;
        this.executor = executor;
    }

    public CompletableFuture<Boolean> replacePendingCode(UUID minecraftUuid, String minecraftUsername, String code, Instant expiresAt) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM " + tables.linkCodes() + " WHERE minecraft_uuid = ?")) {
                        delete.setString(1, minecraftUuid.toString());
                        delete.executeUpdate();
                    }

                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO " + tables.linkCodes()
                                    + " (code, minecraft_uuid, minecraft_username, expires_at, created_at) VALUES (?, ?, ?, ?, ?)")) {
                        insert.setString(1, code);
                        insert.setString(2, minecraftUuid.toString());
                        insert.setString(3, minecraftUsername);
                        insert.setLong(4, expiresAt.toEpochMilli());
                        insert.setLong(5, System.currentTimeMillis());
                        insert.executeUpdate();
                    }

                    connection.commit();
                    return true;
                } catch (SQLIntegrityConstraintViolationException e) {
                    connection.rollback();
                    return false;
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new RepositoryException("Failed to store link code", e);
            }
        }, executor);
    }

    public CompletableFuture<LinkResult> consumeCode(String code, String discordId, String discordUsername, boolean allowRelink) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    Optional<LinkCode> pendingCode = findPendingCodeForUpdate(connection, code);
                    if (pendingCode.isEmpty()) {
                        connection.rollback();
                        return LinkResult.failure(LinkFailureReason.INVALID_OR_EXPIRED_CODE, "Invalid or expired link code.");
                    }

                    LinkCode linkCode = pendingCode.get();
                    if (!allowRelink && hasExistingLink(connection, discordId, linkCode.minecraftUuid())) {
                        connection.rollback();
                        return LinkResult.failure(LinkFailureReason.ALREADY_LINKED, "That Discord or Minecraft account is already linked.");
                    }

                    long now = System.currentTimeMillis();
                    archiveExistingLinks(connection, discordId, linkCode.minecraftUuid(), now, "relink");
                    deleteExistingLinks(connection, discordId, linkCode.minecraftUuid());
                    deletePendingCode(connection, code);

                    LinkedAccount linkedAccount = new LinkedAccount(
                            discordId,
                            linkCode.minecraftUuid(),
                            linkCode.minecraftUsername(),
                            normalizeDiscordUsername(discordUsername, discordId),
                            Instant.ofEpochMilli(now),
                            Instant.ofEpochMilli(now)
                    );
                    insertLinkedAccount(connection, linkedAccount);

                    connection.commit();
                    return LinkResult.success(linkedAccount);
                } catch (SQLException e) {
                    connection.rollback();
                    return LinkResult.failure(LinkFailureReason.DATABASE_ERROR, "Database error while linking account.");
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                return LinkResult.failure(LinkFailureReason.DATABASE_ERROR, "Database error while linking account.");
            }
        }, executor);
    }

    public CompletableFuture<Optional<LinkedAccount>> findByMinecraft(UUID minecraftUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT discord_id, minecraft_uuid, minecraft_username, discord_username, linked_at, updated_at FROM "
                            + tables.accountLinks() + " WHERE minecraft_uuid = ?")) {
                statement.setString(1, minecraftUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(readLinkedAccount(resultSet));
                }
            } catch (SQLException e) {
                throw new RepositoryException("Failed to query Minecraft link", e);
            }
        }, executor);
    }

    public CompletableFuture<Optional<LinkedAccount>> findByDiscordId(String discordId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT discord_id, minecraft_uuid, minecraft_username, discord_username, linked_at, updated_at FROM "
                            + tables.accountLinks() + " WHERE discord_id = ?")) {
                statement.setString(1, discordId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(readLinkedAccount(resultSet));
                }
            } catch (SQLException e) {
                throw new RepositoryException("Failed to query Discord link", e);
            }
        }, executor);
    }

    public CompletableFuture<List<QueuedNotification>> pendingNotifications(int limit, int maxAttempts) {
        return CompletableFuture.supplyAsync(() -> {
            List<QueuedNotification> notifications = new ArrayList<>();
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, discord_id, minecraft_uuid, minecraft_username, discord_username, message, attempts FROM "
                            + tables.notifications()
                            + " WHERE attempts < ? ORDER BY created_at ASC LIMIT ?")) {
                statement.setInt(1, maxAttempts);
                statement.setInt(2, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        notifications.add(new QueuedNotification(
                                resultSet.getLong("id"),
                                resultSet.getString("discord_id"),
                                UUID.fromString(resultSet.getString("minecraft_uuid")),
                                resultSet.getString("minecraft_username"),
                                resultSet.getString("discord_username"),
                                resultSet.getString("message"),
                                resultSet.getInt("attempts")
                        ));
                    }
                }
            } catch (SQLException e) {
                throw new RepositoryException("Failed to load pending notifications", e);
            }
            return notifications;
        }, executor);
    }

    public CompletableFuture<Void> deleteNotification(long id) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + tables.notifications() + " WHERE id = ?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RepositoryException("Failed to delete notification", e);
            }
        }, executor);
    }

    public CompletableFuture<Void> markNotificationFailed(long id, String error) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + tables.notifications()
                            + " SET attempts = attempts + 1, last_error = ? WHERE id = ?")) {
                statement.setString(1, truncate(error, 255));
                statement.setLong(2, id);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RepositoryException("Failed to mark notification failed", e);
            }
        }, executor);
    }

    public CompletableFuture<Integer> deleteExpiredCodes() {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + tables.linkCodes() + " WHERE expires_at <= ?")) {
                statement.setLong(1, System.currentTimeMillis());
                return statement.executeUpdate();
            } catch (SQLException e) {
                throw new RepositoryException("Failed to delete expired link codes", e);
            }
        }, executor);
    }

    public CompletableFuture<Void> queueNotification(LinkedAccount account, String message) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + tables.notifications()
                            + " (discord_id, minecraft_uuid, minecraft_username, discord_username, message, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, account.discordId());
                statement.setString(2, account.minecraftUuid().toString());
                statement.setString(3, account.minecraftUsername());
                statement.setString(4, account.discordUsername());
                statement.setString(5, message);
                statement.setLong(6, System.currentTimeMillis());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RepositoryException("Failed to queue notification", e);
            }
        }, executor);
    }

    private Optional<LinkCode> findPendingCodeForUpdate(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT code, minecraft_uuid, minecraft_username, expires_at FROM " + tables.linkCodes()
                        + " WHERE code = ? AND expires_at > ? FOR UPDATE")) {
            statement.setString(1, code);
            statement.setLong(2, System.currentTimeMillis());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new LinkCode(
                        resultSet.getString("code"),
                        UUID.fromString(resultSet.getString("minecraft_uuid")),
                        resultSet.getString("minecraft_username"),
                        Instant.ofEpochMilli(resultSet.getLong("expires_at"))
                ));
            }
        }
    }

    private boolean hasExistingLink(Connection connection, String discordId, UUID minecraftUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM " + tables.accountLinks() + " WHERE discord_id = ? OR minecraft_uuid = ? LIMIT 1")) {
            statement.setString(1, discordId);
            statement.setString(2, minecraftUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void archiveExistingLinks(Connection connection, String discordId, UUID minecraftUuid, long replacedAt, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + tables.linkHistory()
                        + " (discord_id, minecraft_uuid, minecraft_username, discord_username, linked_at, replaced_at, replacement_reason)"
                        + " SELECT discord_id, minecraft_uuid, minecraft_username, discord_username, linked_at, ?, ? FROM "
                        + tables.accountLinks() + " WHERE discord_id = ? OR minecraft_uuid = ?")) {
            statement.setLong(1, replacedAt);
            statement.setString(2, reason);
            statement.setString(3, discordId);
            statement.setString(4, minecraftUuid.toString());
            statement.executeUpdate();
        }
    }

    private void deleteExistingLinks(Connection connection, String discordId, UUID minecraftUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + tables.accountLinks() + " WHERE discord_id = ? OR minecraft_uuid = ?")) {
            statement.setString(1, discordId);
            statement.setString(2, minecraftUuid.toString());
            statement.executeUpdate();
        }
    }

    private void deletePendingCode(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + tables.linkCodes() + " WHERE code = ?")) {
            statement.setString(1, code);
            statement.executeUpdate();
        }
    }

    private void insertLinkedAccount(Connection connection, LinkedAccount account) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + tables.accountLinks()
                        + " (discord_id, minecraft_uuid, minecraft_username, discord_username, linked_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, account.discordId());
            statement.setString(2, account.minecraftUuid().toString());
            statement.setString(3, account.minecraftUsername());
            statement.setString(4, account.discordUsername());
            statement.setLong(5, account.linkedAt().toEpochMilli());
            statement.setLong(6, account.updatedAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private LinkedAccount readLinkedAccount(ResultSet resultSet) throws SQLException {
        return new LinkedAccount(
                resultSet.getString("discord_id"),
                UUID.fromString(resultSet.getString("minecraft_uuid")),
                resultSet.getString("minecraft_username"),
                resultSet.getString("discord_username"),
                Instant.ofEpochMilli(resultSet.getLong("linked_at")),
                Instant.ofEpochMilli(resultSet.getLong("updated_at"))
        );
    }

    private String normalizeDiscordUsername(String discordUsername, String discordId) {
        if (discordUsername == null || discordUsername.isBlank()) {
            return discordId;
        }
        return discordUsername;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
