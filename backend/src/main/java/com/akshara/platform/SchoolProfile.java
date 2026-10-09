package com.akshara.platform;

import java.util.UUID;

/** A school's name, board and contact details, as shown on its profile page and to every signed-in member. */
public record SchoolProfile(UUID id, String name, String code, Board board, String city, String address,
        String phone, String contactEmail, String udiseCode) {

    static SchoolProfile of(Tenant t) {
        return new SchoolProfile(t.getId(), t.getName(), t.getCode(), t.getBoard(), t.getCity(), t.getAddress(),
                t.getPhone(), t.getContactEmail(), t.getUdiseCode());
    }
}
