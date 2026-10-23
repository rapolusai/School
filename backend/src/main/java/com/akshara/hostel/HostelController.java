package com.akshara.hostel;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.hostel.HostelForms.AllotForm;
import com.akshara.hostel.HostelForms.BedsForm;
import com.akshara.hostel.HostelForms.BlockForm;
import com.akshara.hostel.HostelForms.ComplaintForm;
import com.akshara.hostel.HostelForms.ComplaintStatusForm;
import com.akshara.hostel.HostelForms.DecisionForm;
import com.akshara.hostel.HostelForms.HostelForm;
import com.akshara.hostel.HostelForms.MenuForm;
import com.akshara.hostel.HostelForms.MessAttendanceForm;
import com.akshara.hostel.HostelForms.MoveForm;
import com.akshara.hostel.HostelForms.ResolveForm;
import com.akshara.hostel.HostelForms.RollCallForm;
import com.akshara.hostel.HostelForms.RoomForm;
import com.akshara.hostel.HostelForms.VacateForm;
import com.akshara.hostel.HostelForms.WardenPassForm;
import com.akshara.hostel.HostelViews.AlertView;
import com.akshara.hostel.HostelViews.AllotmentView;
import com.akshara.hostel.HostelViews.BedView;
import com.akshara.hostel.HostelViews.BlockView;
import com.akshara.hostel.HostelViews.BoarderView;
import com.akshara.hostel.HostelViews.ComplaintView;
import com.akshara.hostel.HostelViews.HostelDetail;
import com.akshara.hostel.HostelViews.HostelView;
import com.akshara.hostel.HostelViews.MessDay;
import com.akshara.hostel.HostelViews.OccupancyBoard;
import com.akshara.hostel.HostelViews.PassView;
import com.akshara.hostel.HostelViews.RollCallRegister;
import com.akshara.hostel.HostelViews.RollCallSheet;
import com.akshara.hostel.HostelViews.RollCallSummary;
import com.akshara.hostel.HostelViews.RoomView;
import com.akshara.hostel.HostelViews.StaffRef;
import com.akshara.hostel.HostelViews.StudentBoarding;
import com.akshara.hostel.HostelViews.StudentRef;
import com.akshara.hostel.HostelViews.WeeklyMenu;

/**
 * The hostel API (docs/api/phase-3-hostel.md): set-up and allotment for hostel.manage, reading for hostel.read, and
 * the warden's day-to-day (menu, mess counts, passes, roll call, alerts, complaints) for hostel.warden, limited to
 * the hostels they are warden of.
 */
@RestController
@RequestMapping("/api/hostel")
public class HostelController {

    private final HostelSetupService setup;
    private final AllotmentService allotments;
    private final MessService mess;
    private final HostelPassService passes;
    private final RollCallService rollCall;
    private final ComplaintService complaints;

    HostelController(HostelSetupService setup, AllotmentService allotments, MessService mess,
            HostelPassService passes, RollCallService rollCall, ComplaintService complaints) {
        this.setup = setup;
        this.allotments = allotments;
        this.mess = mess;
        this.passes = passes;
        this.rollCall = rollCall;
        this.complaints = complaints;
    }

    /* ------------------------------------------------------------------ hostels */

    @GetMapping("/hostels")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN)
    public List<HostelView> hostels() {
        return setup.list();
    }

    @GetMapping("/hostels/{id}")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN)
    public HostelDetail hostel(@PathVariable UUID id) {
        return setup.detail(id);
    }

    @GetMapping("/staff")
    @PreAuthorize(HostelAuth.MANAGE)
    public List<StaffRef> staff() {
        return setup.staff();
    }

    @GetMapping("/students")
    @PreAuthorize(HostelAuth.MANAGE)
    public List<StudentRef> students(@RequestParam(required = false) String q) {
        return setup.searchStudents(q);
    }

    @PostMapping("/hostels")
    @PreAuthorize(HostelAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public HostelView create(@Valid @RequestBody HostelForm form) {
        return setup.create(form, HostelAuth.actor());
    }

    @PutMapping("/hostels/{id}")
    @PreAuthorize(HostelAuth.MANAGE)
    public HostelView update(@PathVariable UUID id, @Valid @RequestBody HostelForm form) {
        return setup.update(id, form, HostelAuth.actor());
    }

    @PostMapping("/hostels/{id}/blocks")
    @PreAuthorize(HostelAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public BlockView addBlock(@PathVariable UUID id, @Valid @RequestBody BlockForm form) {
        return setup.addBlock(id, form, HostelAuth.actor());
    }

    @PutMapping("/hostels/{id}/blocks/{blockId}")
    @PreAuthorize(HostelAuth.MANAGE)
    public BlockView updateBlock(@PathVariable UUID id, @PathVariable UUID blockId,
            @Valid @RequestBody BlockForm form) {
        return setup.updateBlock(id, blockId, form, HostelAuth.actor());
    }

    @DeleteMapping("/hostels/{id}/blocks/{blockId}")
    @PreAuthorize(HostelAuth.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeBlock(@PathVariable UUID id, @PathVariable UUID blockId) {
        setup.removeBlock(id, blockId, HostelAuth.actor());
    }

    @PostMapping("/hostels/{id}/blocks/{blockId}/rooms")
    @PreAuthorize(HostelAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public RoomView addRoom(@PathVariable UUID id, @PathVariable UUID blockId, @Valid @RequestBody RoomForm form) {
        return setup.addRoom(id, blockId, form, HostelAuth.actor());
    }

    @PutMapping("/hostels/{id}/rooms/{roomId}")
    @PreAuthorize(HostelAuth.MANAGE)
    public RoomView updateRoom(@PathVariable UUID id, @PathVariable UUID roomId, @Valid @RequestBody RoomForm form) {
        return setup.updateRoom(id, roomId, form, HostelAuth.actor());
    }

    @DeleteMapping("/hostels/{id}/rooms/{roomId}")
    @PreAuthorize(HostelAuth.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRoom(@PathVariable UUID id, @PathVariable UUID roomId) {
        setup.removeRoom(id, roomId, HostelAuth.actor());
    }

    @PostMapping("/hostels/{id}/rooms/{roomId}/beds")
    @PreAuthorize(HostelAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<BedView> addBeds(@PathVariable UUID id, @PathVariable UUID roomId,
            @Valid @RequestBody BedsForm form) {
        return setup.addBeds(id, roomId, form, HostelAuth.actor());
    }

    @DeleteMapping("/hostels/{id}/beds/{bedId}")
    @PreAuthorize(HostelAuth.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeBed(@PathVariable UUID id, @PathVariable UUID bedId) {
        setup.removeBed(id, bedId, HostelAuth.actor());
    }

    @GetMapping("/hostels/{id}/occupancy")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN)
    public OccupancyBoard occupancy(@PathVariable UUID id) {
        return setup.board(id);
    }

    /* ------------------------------------------------------------------ allotment */

    @PostMapping("/allotments")
    @PreAuthorize(HostelAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public AllotmentView allot(@Valid @RequestBody AllotForm form) {
        return allotments.allot(form, HostelAuth.actor());
    }

    @PostMapping("/allotments/{id}/vacate")
    @PreAuthorize(HostelAuth.MANAGE)
    public AllotmentView vacate(@PathVariable UUID id, @Valid @RequestBody VacateForm form) {
        return allotments.vacate(id, form, HostelAuth.actor());
    }

    @PostMapping("/allotments/{id}/move")
    @PreAuthorize(HostelAuth.MANAGE)
    public AllotmentView move(@PathVariable UUID id, @Valid @RequestBody MoveForm form) {
        return allotments.move(id, form, HostelAuth.actor());
    }

    @GetMapping("/boarders")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN)
    public List<BoarderView> boarders(@RequestParam(required = false) UUID hostelId) {
        return allotments.boarders(hostelId);
    }

    @GetMapping("/students/{studentId}/boarding")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN_OR_STUDENTS)
    public StudentBoarding boarding(@PathVariable UUID studentId) {
        return allotments.ofStudent(studentId);
    }

    /* ------------------------------------------------------------------ mess */

    @GetMapping("/hostels/{id}/menu")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN)
    public WeeklyMenu menu(@PathVariable UUID id) {
        return mess.menu(id);
    }

    @PutMapping("/hostels/{id}/menu")
    @PreAuthorize(HostelAuth.WARDEN)
    public WeeklyMenu saveMenu(@PathVariable UUID id, @Valid @RequestBody MenuForm form) {
        return mess.saveMenu(id, form, HostelAuth.actor());
    }

    @GetMapping("/hostels/{id}/mess-attendance")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN)
    public MessDay messDay(@PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date) {
        return mess.day(id, date);
    }

    @PutMapping("/hostels/{id}/mess-attendance/{date}")
    @PreAuthorize(HostelAuth.WARDEN)
    public MessDay markMess(@PathVariable UUID id, @PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate date,
            @Valid @RequestBody MessAttendanceForm form) {
        return mess.mark(id, date, form, HostelAuth.actor());
    }

    /* ------------------------------------------------------------------ passes */

    @GetMapping("/passes")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN)
    public List<PassView> passes(@RequestParam(required = false) UUID hostelId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return passes.list(hostelId, from, to);
    }

    @GetMapping("/passes/{id}")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN)
    public PassView pass(@PathVariable UUID id) {
        return passes.detail(id);
    }

    @PostMapping("/passes")
    @PreAuthorize(HostelAuth.WARDEN)
    @ResponseStatus(HttpStatus.CREATED)
    public PassView raise(@Valid @RequestBody WardenPassForm form) {
        return passes.raise(form, HostelAuth.actor());
    }

    @PostMapping("/passes/{id}/approve")
    @PreAuthorize(HostelAuth.WARDEN)
    public PassView approve(@PathVariable UUID id, @RequestBody(required = false) @Valid DecisionForm form) {
        return passes.approve(id, form, HostelAuth.actor());
    }

    @PostMapping("/passes/{id}/reject")
    @PreAuthorize(HostelAuth.WARDEN)
    public PassView reject(@PathVariable UUID id, @RequestBody(required = false) @Valid DecisionForm form) {
        return passes.reject(id, form, HostelAuth.actor());
    }

    @PostMapping("/passes/{id}/out")
    @PreAuthorize(HostelAuth.WARDEN)
    public PassView out(@PathVariable UUID id) {
        return passes.markOut(id, HostelAuth.actor());
    }

    @PostMapping("/passes/{id}/in")
    @PreAuthorize(HostelAuth.WARDEN)
    public PassView in(@PathVariable UUID id) {
        return passes.markIn(id, HostelAuth.actor());
    }

    /* ------------------------------------------------------------------ roll call */

    @GetMapping("/hostels/{id}/roll-call")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN)
    public RollCallSheet sheet(@PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate night) {
        return rollCall.sheet(id, night);
    }

    @PutMapping("/hostels/{id}/roll-call/{night}")
    @PreAuthorize(HostelAuth.WARDEN)
    public RollCallSheet take(@PathVariable UUID id, @PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate night,
            @Valid @RequestBody RollCallForm form) {
        return rollCall.take(id, night, form, HostelAuth.actor());
    }

    @GetMapping("/hostels/{id}/roll-call/register")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN)
    public RollCallRegister register(@PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return rollCall.register(id, from, to);
    }

    @GetMapping("/hostels/{id}/roll-call/summary")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN)
    public RollCallSummary summary(@PathVariable UUID id, @RequestParam(required = false) String month) {
        return rollCall.summary(id, month);
    }

    @GetMapping("/alerts")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN)
    public List<AlertView> alerts() {
        return rollCall.openAlerts();
    }

    @PostMapping("/alerts/{id}/resolve")
    @PreAuthorize(HostelAuth.WARDEN)
    public AlertView resolve(@PathVariable UUID id, @RequestBody(required = false) @Valid ResolveForm form) {
        return rollCall.resolveAlert(id, form, HostelAuth.actor());
    }

    /* ------------------------------------------------------------------ complaints */

    @GetMapping("/complaints")
    @PreAuthorize(HostelAuth.READ_OR_WARDEN)
    public List<ComplaintView> complaints(@RequestParam(required = false) UUID hostelId,
            @RequestParam(required = false) ComplaintStatus status) {
        return complaints.list(hostelId, status);
    }

    @PostMapping("/complaints")
    @PreAuthorize(HostelAuth.WARDEN)
    @ResponseStatus(HttpStatus.CREATED)
    public ComplaintView raiseComplaint(@Valid @RequestBody ComplaintForm form) {
        return complaints.raise(form, HostelAuth.actor());
    }

    @PostMapping("/complaints/{id}/status")
    @PreAuthorize(HostelAuth.WARDEN)
    public ComplaintView moveComplaint(@PathVariable UUID id, @Valid @RequestBody ComplaintStatusForm form) {
        return complaints.move(id, form, HostelAuth.actor());
    }
}
