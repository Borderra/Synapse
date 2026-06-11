package com.borderra.synapse.config;

public record DatabaseSettings(
        String host,
        int port,
        String name,
        String username,
        String password,
        boolean useSsl,
        String tablePrefix,
        int poolSize,
        long connectionTimeoutMs
) {
    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + name
                + "?useSSL=" + useSsl
                + "&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC";
    }
}
