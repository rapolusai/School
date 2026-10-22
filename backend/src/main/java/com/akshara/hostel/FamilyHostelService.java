package com.akshara.hostel;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.hostel.HostelPeople.Pupil;
import com.akshara.hostel.HostelViews.FamilyHostel;
import com.akshara.hostel.HostelViews.PassView;
import com.akshara.shared.TenantContext;

/**
 * What a parent sees of a boarding child: the bed, the warden's name, the next pass and the last roll-call mark.
 * A day scholar answers {@code boarder = false} so the family app hides its hostel card.
 */
@Service
@Transactional(readOnly = true)
public class FamilyHostelService {

    private final AllotmentRepository allotments;
    private final HostelRepository hostels;
    private final BlockRepository blocks;
    private final RoomRepository rooms;
    private final BedRepository beds;
    private final RollCallRepository rollCalls;
    private final RollCallEntryRepository entries;
    private final HostelPassRepository passes;
    private final HostelPeople people;
    private final HostelPassService passService;

    FamilyHostelService(AllotmentRepository allotments, HostelRepository hostels, BlockRepository blocks,
            RoomRepository rooms, BedRepository beds, RollCallRepository rollCalls, RollCallEntryRepository entries,
            HostelPassRepository passes, HostelPeople people, HostelPassService passService) {
        this.allotments = allotments;
        this.hostels = hostels;
        this.blocks = blocks;
        this.rooms = rooms;
        this.beds = beds;
        this.rollCalls = rollCalls;
        this.entries = entries;
        this.passes = passes;
        this.people = people;
        this.passService = passService;
    }

    public FamilyHostel ofChild(UUID parentUserId, UUID studentId) {
        TenantContext.require();
        Pupil pupil = people.requireChild(parentUserId, studentId);
        Allotment a = allotments.activeOfStudent(studentId).orElse(null);
        if (a == null) {
            return new FamilyHostel(studentId, pupil.fullName(), pupil.className(), false, null, null, null, null,
                    null, null, null, null, false, List.of());
        }
        Hostel hostel = hostels.findById(a.getHostelId()).orElse(null);
        Room room = rooms.findById(a.getRoomId()).orElse(null);
        Block block = room == null ? null : blocks.findById(room.getBlockId()).orElse(null);
        Bed bed = beds.findById(a.getBedId()).orElse(null);
        String warden = hostel == null || hostel.getWardenUserId() == null ? null
                : people.staffNames(List.of(hostel.getWardenUserId())).get(hostel.getWardenUserId());
        List<PassView> passViews = passService.views(passes.ofStudents(List.of(studentId)));
        LocalDate today = HostelText.today();
        PassView next = passViews.stream().filter(p -> p.status().open() && !p.toDate().isBefore(today))
                .min(Comparator.comparing(PassView::fromDate)).orElse(null);
        LocalDate lastNight = null;
        RollCallStatus lastStatus = null;
        List<RollCall> recent = rollCalls.between(a.getHostelId(), today.minusDays(30), today);
        for (RollCall call : recent) {
            List<RollCallEntry> mine = entries.ofStudentIn(studentId, List.of(call.getId()));
            if (!mine.isEmpty()) {
                lastNight = call.getNight();
                lastStatus = mine.get(0).getStatus();
                break;
            }
        }
        return new FamilyHostel(studentId, pupil.fullName(), pupil.className(), true,
                hostel == null ? null : hostel.getName(), block == null ? null : block.getName(),
                room == null ? null : room.getNumber(), bed == null ? null : bed.getLabel(), warden, next, lastNight,
                lastStatus, pupil.active(), passViews);
    }
}
