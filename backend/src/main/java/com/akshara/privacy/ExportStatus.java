package com.akshara.privacy;

/** A data export's file: downloadable, or deleted because it expired, was replaced, or the child's data was erased. */
public enum ExportStatus {
    READY, EXPIRED, REPLACED, ERASED
}
