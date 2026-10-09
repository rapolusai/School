package com.akshara.timetable;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.shared.CurrentUser;
import com.akshara.timetable.TimetableViews.FamilyTimetable;

/** A student's own section timetable, and a parent's view of their child's. Anyone else's child is 404. */
@RestController
public class FamilyTimetableController {

    private final FamilyTimetableService family;

    FamilyTimetableController(FamilyTimetableService family) {
        this.family = family;
    }

    @GetMapping("/api/me/timetable")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public FamilyTimetable mine() {
        return family.forStudent(CurrentUser.requireId());
    }

    @GetMapping("/api/me/children/{studentId}/timetable")
    @PreAuthorize("hasAuthority('child.view')")
    public FamilyTimetable child(@PathVariable UUID studentId) {
        return family.forChild(CurrentUser.requireId(), studentId);
    }
}
