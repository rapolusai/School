package com.akshara.hostel;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;

/**
 * Which hostels the signed-in person may work with: every one for hostel.read or hostel.manage, only the hostels
 * they are warden of for hostel.warden alone. A hostel outside the scope answers 404, like another school's.
 */
@Component
class HostelScope {

    private final HostelRepository hostels;

    HostelScope(HostelRepository hostels) {
        this.hostels = hostels;
    }

    /** The hostels in scope, by name. */
    List<Hostel> visible() {
        if (HostelAuth.canRead()) {
            return hostels.findAllByOrderByNameAsc();
        }
        return hostels.findByWardenUserIdOrderByNameAsc(CurrentUser.requireId());
    }

    Set<UUID> visibleIds() {
        return visible().stream().map(Hostel::getId).collect(Collectors.toSet());
    }

    /** True when the signed-in person sees every hostel (not only their own). */
    boolean seesAll() {
        return HostelAuth.canRead();
    }

    /** The hostel behind a path id, or 404 when it is another school's or outside the warden's scope. */
    Hostel require(UUID hostelId) {
        Hostel hostel = hostels.findById(hostelId).orElseThrow(() -> ApiException.notFound("Hostel"));
        if (!seesAll() && !CurrentUser.requireId().equals(hostel.getWardenUserId())) {
            throw ApiException.notFound("Hostel");
        }
        return hostel;
    }

    /** True when the signed-in person may run the hostel's day-to-day (its warden, or anyone with hostel.manage). */
    boolean canWarden(Hostel hostel) {
        if (HostelAuth.canManage()) {
            return true;
        }
        return HostelAuth.isWarden() && CurrentUser.id().map(id -> id.equals(hostel.getWardenUserId())).orElse(false);
    }

    /** The hostel for a warden's action: 404 outside the scope, 403 for a reader who is not its warden. */
    Hostel requireWarden(UUID hostelId) {
        Hostel hostel = require(hostelId);
        if (!canWarden(hostel)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "Not your hostel",
                    "Only the warden of " + hostel.getName() + " or the school admin can do this.");
        }
        return hostel;
    }
}
