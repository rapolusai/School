package com.akshara.hostel;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.hostel.HostelForms.ComplaintForm;
import com.akshara.hostel.HostelForms.ComplaintStatusForm;
import com.akshara.hostel.HostelViews.ComplaintView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Complaints and maintenance requests about a room (plumbing, electrical, furniture, cleaning, other), raised by
 * the warden or the office and moved OPEN → IN_PROGRESS → RESOLVED. Audited.
 */
@Service
@Transactional
public class ComplaintService {

    private final ComplaintRepository complaints;
    private final RoomRepository rooms;
    private final BlockRepository blocks;
    private final HostelRepository hostels;
    private final HostelScope scope;
    private final AuditService audit;

    ComplaintService(ComplaintRepository complaints, RoomRepository rooms, BlockRepository blocks,
            HostelRepository hostels, HostelScope scope, AuditService audit) {
        this.complaints = complaints;
        this.rooms = rooms;
        this.blocks = blocks;
        this.hostels = hostels;
        this.scope = scope;
        this.audit = audit;
    }

    /** Complaints of the hostels in scope (one hostel when given), latest first; resolved ones too. */
    @Transactional(readOnly = true)
    public List<ComplaintView> list(UUID hostelId, ComplaintStatus status) {
        TenantContext.require();
        Set<UUID> ids = hostelId == null ? scope.visibleIds() : Set.of(scope.require(hostelId).getId());
        if (ids.isEmpty()) {
            return List.of();
        }
        return views(complaints.ofHostels(ids).stream().filter(c -> status == null || c.getStatus() == status)
                .toList());
    }

    public ComplaintView raise(ComplaintForm form, Actor actor) {
        TenantContext.require();
        Room room = rooms.findById(form.roomId()).orElseThrow(() -> ApiException.badRequest(
                "Pick a room of this school's hostels.", "roomId"));
        Hostel hostel = scope.requireWarden(room.getHostelId());
        Complaint c = complaints.save(new Complaint(room.getHostelId(), room.getId(), form.category(),
                form.description().trim(), actor.name(), Instant.now()));
        Map<String, Object> d = details(c, hostel, room);
        audit.record(actor, "hostel_complaint.raised", "hostel_complaint", c.getId(), d);
        complaints.flush();
        return views(List.of(c)).get(0);
    }

    public ComplaintView move(UUID id, ComplaintStatusForm form, Actor actor) {
        TenantContext.require();
        Complaint c = complaints.findById(id).orElseThrow(() -> ApiException.notFound("Complaint"));
        Hostel hostel = scope.requireWarden(c.getHostelId());
        if (c.getStatus() == ComplaintStatus.RESOLVED) {
            throw new ApiException(HttpStatus.CONFLICT, "Already resolved", "This complaint is already resolved.");
        }
        if (c.getStatus() == form.status()) {
            throw new ApiException(HttpStatus.CONFLICT, "No change", "The complaint is already "
                    + form.status().name().toLowerCase().replace('_', ' ') + ".");
        }
        String note = HostelText.clean(form.note());
        if (form.status() == ComplaintStatus.RESOLVED && note == null) {
            throw ApiException.badRequest("Say what was done.", "note");
        }
        c.move(form.status(), note, Instant.now());
        Room room = rooms.findById(c.getRoomId()).orElse(null);
        Map<String, Object> d = details(c, hostel, room);
        d.put("note", note == null ? "" : note);
        audit.record(actor, "hostel_complaint.updated", "hostel_complaint", id, d);
        complaints.flush();
        return views(List.of(c)).get(0);
    }

    List<ComplaintView> views(Collection<Complaint> rows) {
        Map<UUID, String> hostelNames = hostels.findAllById(rows.stream().map(Complaint::getHostelId).toList())
                .stream().collect(Collectors.toMap(Hostel::getId, Hostel::getName));
        Map<UUID, Room> roomById = rooms.findAllById(rows.stream().map(Complaint::getRoomId).toList()).stream()
                .collect(Collectors.toMap(Room::getId, r -> r));
        Map<UUID, String> blockNames = blocks.findAllById(roomById.values().stream().map(Room::getBlockId).toList())
                .stream().collect(Collectors.toMap(Block::getId, Block::getName));
        return rows.stream().map(c -> {
            Room r = roomById.get(c.getRoomId());
            return new ComplaintView(c.getId(), c.getHostelId(), hostelNames.get(c.getHostelId()), c.getRoomId(),
                    r == null ? null : r.getNumber(), r == null ? null : blockNames.get(r.getBlockId()),
                    c.getCategory(), c.getDescription(), c.getStatus(), c.getRaisedByName(), c.getResolutionNote(),
                    c.getResolvedAt(), c.getCreatedAt());
        }).toList();
    }

    private static Map<String, Object> details(Complaint c, Hostel hostel, Room room) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("hostel", hostel.getName());
        d.put("room", room == null ? "" : room.getNumber());
        d.put("category", c.getCategory().name());
        d.put("status", c.getStatus().name());
        return d;
    }
}
