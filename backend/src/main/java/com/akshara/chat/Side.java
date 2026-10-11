package com.akshara.chat;

/** The two sides of a conversation: the parent (a guardian's sign-in) and the member of staff. */
public enum Side {
    PARENT, STAFF;

    public Side other() {
        return this == PARENT ? STAFF : PARENT;
    }
}
