package com.akshara.library;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.library.LibraryForms.CollectForm;
import com.akshara.library.LibraryForms.HoldForm;
import com.akshara.library.LibraryForms.IssueForm;
import com.akshara.library.LibraryForms.LostForm;
import com.akshara.library.LibraryForms.ReturnForm;
import com.akshara.library.LibraryForms.WaiveForm;
import com.akshara.library.LibraryViews.FinePage;
import com.akshara.library.LibraryViews.FineReceipt;
import com.akshara.library.LibraryViews.FineView;
import com.akshara.library.LibraryViews.HoldView;
import com.akshara.library.LibraryViews.LoanPage;
import com.akshara.library.LibraryViews.LoanView;
import com.akshara.library.LibraryViews.Member;
import com.akshara.library.LibraryViews.MemberAccount;
import com.akshara.library.LibraryViews.ReturnResult;

/** The issue and return desk: members, loans, returns, renewals, lost copies, holds and fines. library.manage. */
@RestController
@RequestMapping("/api/library")
@PreAuthorize(LibraryAuth.MANAGE)
public class LibraryDeskController {

    static final int MAX_MEMBER_RESULTS = 20;

    private final CirculationService circulation;
    private final HoldService holds;
    private final FineService fines;
    private final LibraryMembers members;

    LibraryDeskController(CirculationService circulation, HoldService holds, FineService fines,
            LibraryMembers members) {
        this.circulation = circulation;
        this.holds = holds;
        this.fines = fines;
        this.members = members;
    }

    /** Active students and staff by name, admission number or employee code (at least 2 characters). */
    @GetMapping("/members")
    public List<Member> members(@RequestParam @Size(min = 2, max = 100) String q) {
        return members.search(q, MAX_MEMBER_RESULTS);
    }

    /** A member's books on loan, holds, fines due, recent history and whether they may borrow now. */
    @GetMapping("/members/{type}/{id}")
    public MemberAccount account(@PathVariable MemberType type, @PathVariable UUID id) {
        return circulation.account(new MemberRef(type, id), LoanMath.today());
    }

    @PostMapping("/loans")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanView issue(@Valid @RequestBody IssueForm form) {
        return circulation.issue(form, LibraryAuth.actor(), LoanMath.today());
    }

    @GetMapping("/loans")
    public LoanPage loans(@RequestParam(required = false) LoanStatus status,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(required = false) MemberType memberType,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(CirculationService.MAX_PAGE_SIZE) int size) {
        return circulation.list(status, overdue, memberType, memberId, page, size, LoanMath.today());
    }

    @PostMapping("/loans/{id}/renew")
    public LoanView renew(@PathVariable UUID id) {
        return circulation.renew(id, LibraryAuth.actor(), LoanMath.today());
    }

    @PostMapping("/loans/{id}/lost")
    public ReturnResult lost(@PathVariable UUID id, @Valid @RequestBody LostForm form) {
        return circulation.lost(id, form, LibraryAuth.actor(), LoanMath.today());
    }

    /** Takes a copy back by its accession number; any overdue fine is worked out now. */
    @PostMapping("/returns")
    public ReturnResult giveBack(@Valid @RequestBody ReturnForm form) {
        return circulation.giveBack(form, LibraryAuth.actor(), LoanMath.today());
    }

    @GetMapping("/reservations")
    public List<HoldView> reservations() {
        return holds.active(LoanMath.today());
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldView reserve(@Valid @RequestBody HoldForm form) {
        return holds.place(form, LibraryAuth.actor(), LoanMath.today());
    }

    @PostMapping("/reservations/{id}/cancel")
    public HoldView cancel(@PathVariable UUID id) {
        return holds.cancel(id, LibraryAuth.actor(), LoanMath.today());
    }

    @GetMapping("/fines")
    public FinePage fines(@RequestParam(required = false) FineStatus status,
            @RequestParam(required = false) MemberType memberType,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(FineService.MAX_PAGE_SIZE) int size) {
        return fines.list(status, memberType, memberId, page, size);
    }

    @PostMapping("/fines/collect")
    public FineReceipt collect(@Valid @RequestBody CollectForm form) {
        return fines.collect(form, LibraryAuth.actor(), Instant.now(), LoanMath.today());
    }

    @PostMapping("/fines/{id}/waive")
    public FineView waive(@PathVariable UUID id, @Valid @RequestBody WaiveForm form) {
        return fines.waive(id, form, LibraryAuth.actor(), Instant.now());
    }

    /** A fine receipt by its number (LIBF/2026-27/000001), to print again. */
    @GetMapping("/fines/receipt")
    public FineReceipt receipt(@RequestParam @Size(max = 30) String no) {
        return fines.receipt(no);
    }
}
