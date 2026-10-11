package com.akshara.library;

import java.util.UUID;

/** A library member: one of the school's students (their student id) or a member of its staff (their sign-in id). */
public record MemberRef(MemberType type, UUID id) {

    public MemberRef {
        if (type == null || id == null) {
            throw new IllegalArgumentException("A member needs a type and an id");
        }
    }

    static MemberRef of(MemberType type, UUID studentId, UUID staffUserId) {
        return new MemberRef(type, type == MemberType.STUDENT ? studentId : staffUserId);
    }

    static MemberRef student(UUID studentId) {
        return new MemberRef(MemberType.STUDENT, studentId);
    }

    static MemberRef staff(UUID userId) {
        return new MemberRef(MemberType.STAFF, userId);
    }

    UUID studentId() {
        return type == MemberType.STUDENT ? id : null;
    }

    UUID staffUserId() {
        return type == MemberType.STAFF ? id : null;
    }
}
