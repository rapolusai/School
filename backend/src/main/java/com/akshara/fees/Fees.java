package com.akshara.fees;

import org.springframework.http.HttpStatus;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;

/** Small helpers shared by the fee services. */
final class Fees {

    private Fees() {
    }

    /** The given actor, or the signed-in user. Names are copied onto receipts and approvals. */
    static Actor actor(Actor actor) {
        if (actor != null) {
            return actor;
        }
        return new Actor(CurrentUser.id().orElse(null), CurrentUser.name().orElse("Staff"));
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static ApiException conflict(String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, title, detail);
    }

    /** "₹1,84,300" or "₹1,84,300.50": Indian digit grouping, for messages. */
    static String rupees(long paise) {
        boolean negative = paise < 0;
        long abs = Math.abs(paise);
        String whole = Long.toString(abs / 100);
        StringBuilder grouped = new StringBuilder();
        if (whole.length() <= 3) {
            grouped.append(whole);
        } else {
            String head = whole.substring(0, whole.length() - 3);
            String tail = whole.substring(whole.length() - 3);
            StringBuilder h = new StringBuilder();
            for (int i = head.length(); i > 0; i -= 2) {
                h.insert(0, head.substring(Math.max(0, i - 2), i));
                if (i - 2 > 0) {
                    h.insert(0, ',');
                }
            }
            grouped.append(h).append(',').append(tail);
        }
        long rest = abs % 100;
        return (negative ? "-" : "") + "₹" + grouped + (rest == 0 ? "" : String.format(".%02d", rest));
    }
}
