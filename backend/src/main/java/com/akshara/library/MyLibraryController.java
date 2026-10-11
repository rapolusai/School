package com.akshara.library;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.library.LibraryViews.MyLibrary;
import com.akshara.shared.CurrentUser;

/** The signed-in person's library: a parent's children's loans, a student's own, or a staff member's own. */
@RestController
public class MyLibraryController {

    private final FamilyLibraryService family;

    MyLibraryController(FamilyLibraryService family) {
        this.family = family;
    }

    @GetMapping("/api/me/library")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public MyLibrary mine() {
        return family.mine(CurrentUser.requireId(), LoanMath.today());
    }
}
