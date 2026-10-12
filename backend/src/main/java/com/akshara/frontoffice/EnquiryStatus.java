package com.akshara.frontoffice;

/** OPEN when logged, FOLLOW_UP while the office is to call back, CLOSED when answered, CONVERTED into an application. */
public enum EnquiryStatus {
    OPEN, FOLLOW_UP, CLOSED, CONVERTED;

    public boolean open() {
        return this == OPEN || this == FOLLOW_UP;
    }
}
