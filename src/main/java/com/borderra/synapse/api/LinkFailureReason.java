package com.borderra.synapse.api;

public enum LinkFailureReason {
    NONE,
    INVALID_OR_EXPIRED_CODE,
    ALREADY_LINKED,
    DATABASE_ERROR
}
