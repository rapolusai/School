package com.akshara.chat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.chat.ChatPeople.StudentInfo;
import com.akshara.chat.PtmService.Loaded;
import com.akshara.chat.PtmViews.AttendanceSummary;
import com.akshara.chat.PtmViews.EventDetail;
import com.akshara.chat.PtmViews.EventSection;
import com.akshara.chat.PtmViews.SectionAttendance;
import com.akshara.chat.PtmViews.TeacherAttendance;
import com.akshara.shared.TenantContext;

/**
 * The PTM attendance summary for the reports hub: per invited class, how many students' families booked, came, did
 * not come or are not marked yet; per teacher, their slots and bookings the same way. Also as CSV (UTF-8 with a byte
 * order mark, CRLF, formula-safe).
 */
@Service
@Transactional(readOnly = true)
class PtmReports {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String BOM = "﻿";

    private final PtmService ptm;

    PtmReports(PtmService ptm) {
        this.ptm = ptm;
    }

    AttendanceSummary attendance(UUID eventId, LocalDate today) {
        TenantContext.require();
        PtmEvent e = ptm.find(eventId);
        EventDetail detail = ptm.detail(e, today);
        Loaded loaded = ptm.load(List.of(e));
        List<PtmBooking> active = loaded.activeBookings(e.getId());
        Map<UUID, List<PtmBooking>> byStudent = new HashMap<>();
        active.forEach(b -> byStudent.computeIfAbsent(b.getStudentId(), k -> new ArrayList<>()).add(b));
        List<SectionAttendance> sections = new ArrayList<>();
        int[] total = new int[5];
        for (EventSection s : detail.sections()) {
            Set<UUID> booked = new HashSet<>();
            int attended = 0;
            int noShow = 0;
            int notMarked = 0;
            for (Map.Entry<UUID, List<PtmBooking>> entry : byStudent.entrySet()) {
                StudentInfo st = loaded.students.get(entry.getKey());
                if (st == null || !s.id().equals(st.sectionId())) {
                    continue;
                }
                booked.add(entry.getKey());
                List<PtmBooking> list = entry.getValue();
                if (list.stream().anyMatch(b -> b.getAttendance() == PtmAttendance.ATTENDED)) {
                    attended++;
                } else if (list.stream().anyMatch(b -> b.getAttendance() == PtmAttendance.NO_SHOW)) {
                    noShow++;
                } else {
                    notMarked++;
                }
            }
            sections.add(new SectionAttendance(s.id(), s.label(), s.students(), booked.size(), attended, noShow,
                    notMarked));
            total[0] += s.students();
            total[1] += booked.size();
            total[2] += attended;
            total[3] += noShow;
            total[4] += notMarked;
        }
        List<TeacherAttendance> teachers = new ArrayList<>();
        for (PtmTeacher t : loaded.teachersOf(e.getId())) {
            List<PtmSlot> slots = loaded.slotsOf(t);
            int slotCount = 0;
            int booked = 0;
            int attended = 0;
            int noShow = 0;
            for (PtmSlot slot : slots) {
                PtmBooking b = loaded.activeOf(slot);
                if (b == null) {
                    if (!slot.isBlocked()) {
                        slotCount++;
                    }
                    continue;
                }
                slotCount++;
                booked++;
                if (b.getAttendance() == PtmAttendance.ATTENDED) {
                    attended++;
                } else if (b.getAttendance() == PtmAttendance.NO_SHOW) {
                    noShow++;
                }
            }
            teachers.add(new TeacherAttendance(t.getTeacherUserId(), loaded.nameOf(t.getTeacherUserId()), slotCount,
                    booked, attended, noShow, booked - attended - noShow));
        }
        SectionAttendance totals = new SectionAttendance(null, "Total", total[0], total[1], total[2], total[3],
                total[4]);
        return new AttendanceSummary(e.getId(), e.getTitle(), e.getMeetingDate(), e.getStatus().name(), sections,
                teachers, totals);
    }

    byte[] attendanceCsv(UUID eventId, LocalDate today) {
        AttendanceSummary s = attendance(eventId, today);
        StringBuilder out = new StringBuilder(BOM);
        row(out, "Meeting", "Date", "Type", "Name", "Students or slots", "Booked", "Attended", "No-show",
                "Not marked");
        for (SectionAttendance a : s.sections()) {
            row(out, s.title(), s.date(), "Class", a.label(), a.students(), a.booked(), a.attended(), a.noShow(),
                    a.notMarked());
        }
        SectionAttendance t = s.total();
        row(out, s.title(), s.date(), "Total", t.label(), t.students(), t.booked(), t.attended(), t.noShow(),
                t.notMarked());
        for (TeacherAttendance a : s.teachers()) {
            row(out, s.title(), s.date(), "Teacher", a.name(), a.slots(), a.booked(), a.attended(), a.noShow(),
                    a.notMarked());
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static ResponseEntity<byte[]> download(byte[] csv, String fileName) {
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                        .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv);
    }

    private static void row(StringBuilder out, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            Object c = cells[i];
            if (c == null) {
                continue;
            }
            out.append(c instanceof Number || c instanceof LocalDate ? c.toString() : text(c.toString()));
        }
        out.append("\r\n");
    }

    /** Quotes when needed; text starting with = + - @ or a tab gets a leading apostrophe so it is never a formula. */
    static String text(String value) {
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
