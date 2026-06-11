package com.borderra.synapse.database;

public record TableNames(
        String linkCodes,
        String accountLinks,
        String linkHistory,
        String notifications
) {
    public static TableNames fromPrefix(String prefix) {
        return new TableNames(
                prefix + "link_codes",
                prefix + "account_links",
                prefix + "link_history",
                prefix + "notifications"
        );
    }
}
