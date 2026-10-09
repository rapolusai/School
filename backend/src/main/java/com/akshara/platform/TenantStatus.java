package com.akshara.platform;

public enum TenantStatus {
    TRIAL, ACTIVE, PAST_DUE, SUSPENDED;

    /** Whether people at the school may sign in. */
    public boolean allowsSignIn() {
        return this != SUSPENDED;
    }
}
