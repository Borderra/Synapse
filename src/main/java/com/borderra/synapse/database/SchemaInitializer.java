package com.borderra.synapse.database;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class SchemaInitializer {
    private SchemaInitializer() {
    }

    public static void initialize(DataSource dataSource, TableNames tables) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tables.linkCodes() + " ("
                    + "code VARCHAR(12) PRIMARY KEY,"
                    + "minecraft_uuid CHAR(36) NOT NULL UNIQUE,"
                    + "minecraft_username VARCHAR(32) NOT NULL,"
                    + "expires_at BIGINT NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "INDEX idx_expires_at (expires_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tables.accountLinks() + " ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "discord_id VARCHAR(32) NOT NULL UNIQUE,"
                    + "minecraft_uuid CHAR(36) NOT NULL UNIQUE,"
                    + "minecraft_username VARCHAR(32) NOT NULL,"
                    + "discord_username VARCHAR(128) NOT NULL,"
                    + "linked_at BIGINT NOT NULL,"
                    + "updated_at BIGINT NOT NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tables.linkHistory() + " ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "discord_id VARCHAR(32) NOT NULL,"
                    + "minecraft_uuid CHAR(36) NOT NULL,"
                    + "minecraft_username VARCHAR(32) NOT NULL,"
                    + "discord_username VARCHAR(128) NOT NULL,"
                    + "linked_at BIGINT NOT NULL,"
                    + "replaced_at BIGINT NOT NULL,"
                    + "replacement_reason VARCHAR(32) NOT NULL,"
                    + "INDEX idx_discord_id (discord_id),"
                    + "INDEX idx_minecraft_uuid (minecraft_uuid)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + tables.notifications() + " ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "discord_id VARCHAR(32) NOT NULL,"
                    + "minecraft_uuid CHAR(36) NOT NULL,"
                    + "minecraft_username VARCHAR(32) NOT NULL,"
                    + "discord_username VARCHAR(128) NOT NULL,"
                    + "message TEXT NULL,"
                    + "attempts INT NOT NULL DEFAULT 0,"
                    + "last_error VARCHAR(255) NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "INDEX idx_attempts (attempts),"
                    + "INDEX idx_created_at (created_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        }
    }
}
