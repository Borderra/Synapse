package com.borderra.synapse.api;

import java.util.Optional;

public record LinkResult(
        boolean success,
        LinkFailureReason reason,
        Optional<LinkedAccount> account,
        String message
) {
    public static LinkResult success(LinkedAccount account) {
        return new LinkResult(true, LinkFailureReason.NONE, Optional.of(account), "Linked successfully.");
    }

    public static LinkResult failure(LinkFailureReason reason, String message) {
        return new LinkResult(false, reason, Optional.empty(), message);
    }
}
