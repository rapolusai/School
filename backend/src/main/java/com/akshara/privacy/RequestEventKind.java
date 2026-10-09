package com.akshara.privacy;

/** A step on a data request's timeline. */
public enum RequestEventKind {
    SUBMITTED, ASSIGNED, STAFF_REPLY, PARENT_REPLY, EXPORT_READY, EXPORT_DOWNLOADED, ERASED, CLOSED
}
