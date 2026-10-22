package com.akshara.hostel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
import com.akshara.hostel.HostelForms.BedsForm;
import com.akshara.hostel.HostelForms.BlockForm;
import com.akshara.hostel.HostelForms.HostelForm;
import com.akshara.hostel.HostelForms.RoomForm;
import com.akshara.hostel.HostelPeople.Pupil;
import com.akshara.hostel.HostelViews.BedView;
import com.akshara.hostel.HostelViews.BlockView;
import com.akshara.hostel.HostelViews.HostelDetail;
import com.akshara.hostel.HostelViews.HostelView;
import com.akshara.hostel.HostelViews.OccupancyBoard;
import com.akshara.hostel.HostelViews.RoomCard;
import com.akshara.hostel.HostelViews.RoomView;
import com.akshara.hostel.HostelViews.StaffRef;
import com.akshara.hostel.HostelViews.StudentRef;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The hostel set-up: hostels with their warden, blocks, rooms and beds, and the occupancy board of rooms as cards
 * with their free beds. Writes need hostel.manage; wardens read only their hostels (see {@link HostelScope}).
 */
@Service
@Transactional
public class HostelSetupService {

    private final HostelRepository hostels;
    private final BlockRepository blocks;
    private final RoomRepository rooms;
    private final BedRepository beds;
    private final AllotmentRepository allotments;
    private final ComplaintRepository complaints;
    private final HostelPeople people;
    private final HostelScope scope;
    private final AuditService audit;

    HostelSetupService(HostelRepository hostels, BlockRepository blocks, RoomRepository rooms, BedRepository beds,
            AllotmentRepository allotments, ComplaintRepository complaints, HostelPeople people, HostelScope scope,
            AuditService audit) {
        this.hostels = hostels;
        this.blocks = blocks;
        this.rooms = rooms;
        this.beds = beds;
        this.allotments = allotments;
        this.complaints = complaints;
        this.people = people;
        this.scope = scope;
        this.audit = audit;
    }

    /* ------------------------------------------------------------------ hostels */

    @Transactional(readOnly = true)
    public List<HostelView> list() {
        TenantContext.require();
        return views(scope.visible());
    }

    @Transactional(readOnly = true)
    public HostelDetail detail(UUID id) {
        TenantContext.require();
        Hostel hostel = scope.require(id);
        List<Block> blockRows = blocks.findByHostelIdOrderByNameAsc(id);
        return new HostelDetail(views(List.of(hostel)).get(0), blockViews(blockRows), roomViews(
                rooms.findByHostelIdOrderByFloorAscNumberAsc(id), blockRows));
    }

    @Transactional(readOnly = true)
    public List<StaffRef> staff() {
        TenantContext.require();
        return people.activeStaff();
    }

    @Transactional(readOnly = true)
    public List<StudentRef> searchStudents(String q) {
        TenantContext.require();
        return people.searchStudents(q, 20);
    }

    public HostelView create(HostelForm form, Actor actor) {
        TenantContext.require();
        String name = form.name().trim();
        if (hostels.existsByNameIgnoreCase(name)) {
            throw new ApiException(HttpStatus.CONFLICT, "Hostel exists", "There is already a hostel named " + name
                    + ".", Map.of("name", "A hostel with this name exists."));
        }
        StaffRef warden = form.wardenUserId() == null ? null : people.requireStaff(form.wardenUserId(),
                "wardenUserId");
        Hostel hostel = hostels.save(new Hostel(name, form.type(), warden == null ? null : warden.id(),
                HostelText.clean(form.address()), Instant.now()));
        audit.record(actor, "hostel.created", "hostel", hostel.getId(), details(hostel, warden));
        hostels.flush();
        return views(List.of(hostel)).get(0);
    }

    public HostelView update(UUID id, HostelForm form, Actor actor) {
        TenantContext.require();
        Hostel hostel = hostels.findById(id).orElseThrow(() -> ApiException.notFound("Hostel"));
        String name = form.name().trim();
        if (!hostel.getName().equalsIgnoreCase(name) && hostels.existsByNameIgnoreCase(name)) {
            throw new ApiException(HttpStatus.CONFLICT, "Hostel exists", "There is already a hostel named " + name
                    + ".", Map.of("name", "A hostel with this name exists."));
        }
        StaffRef warden = form.wardenUserId() == null ? null : people.requireStaff(form.wardenUserId(),
                "wardenUserId");
        hostel.update(name, form.type(), warden == null ? null : warden.id(), HostelText.clean(form.address()),
                form.active() == null || form.active());
        audit.record(actor, "hostel.updated", "hostel", id, details(hostel, warden));
        hostels.flush();
        return views(List.of(hostel)).get(0);
    }

    /* ------------------------------------------------------------------ blocks */

    public BlockView addBlock(UUID hostelId, BlockForm form, Actor actor) {
        TenantContext.require();
        Hostel hostel = hostels.findById(hostelId).orElseThrow(() -> ApiException.notFound("Hostel"));
        String name = form.name().trim();
        if (blocks.existsByHostelIdAndNameIgnoreCase(hostelId, name)) {
            throw new ApiException(HttpStatus.CONFLICT, "Block exists", hostel.getName() + " already has a block "
                    + "named " + name + ".", Map.of("name", "A block with this name exists."));
        }
        Block block = blocks.save(new Block(hostelId, name, form.floors() == null ? 1 : form.floors(),
                Instant.now()));
        audit.record(actor, "hostel_block.created", "hostel_block", block.getId(), Map.of("hostel",
                hostel.getName(), "block", block.getName(), "floors", block.getFloors()));
        blocks.flush();
        return blockViews(List.of(block)).get(0);
    }

    public BlockView updateBlock(UUID hostelId, UUID blockId, BlockForm form, Actor actor) {
        TenantContext.require();
        Hostel hostel = hostels.findById(hostelId).orElseThrow(() -> ApiException.notFound("Hostel"));
        Block block = requireBlock(hostelId, blockId);
        String name = form.name().trim();
        if (!block.getName().equalsIgnoreCase(name) && blocks.existsByHostelIdAndNameIgnoreCase(hostelId, name)) {
            throw new ApiException(HttpStatus.CONFLICT, "Block exists", hostel.getName() + " already has a block "
                    + "named " + name + ".", Map.of("name", "A block with this name exists."));
        }
        block.update(name, form.floors() == null ? block.getFloors() : form.floors());
        audit.record(actor, "hostel_block.updated", "hostel_block", blockId, Map.of("hostel", hostel.getName(),
                "block", block.getName(), "floors", block.getFloors()));
        blocks.flush();
        return blockViews(List.of(block)).get(0);
    }

    public void removeBlock(UUID hostelId, UUID blockId, Actor actor) {
        TenantContext.require();
        Hostel hostel = hostels.findById(hostelId).orElseThrow(() -> ApiException.notFound("Hostel"));
        Block block = requireBlock(hostelId, blockId);
        if (rooms.countByBlockId(blockId) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Block has rooms",
                    "Remove the rooms of " + block.getName() + " first.");
        }
        blocks.delete(block);
        audit.record(actor, "hostel_block.removed", "hostel_block", blockId, Map.of("hostel", hostel.getName(),
                "block", block.getName()));
        blocks.flush();
    }

    /* ------------------------------------------------------------------ rooms */

    public RoomView addRoom(UUID hostelId, UUID blockId, RoomForm form, Actor actor) {
        TenantContext.require();
        Hostel hostel = hostels.findById(hostelId).orElseThrow(() -> ApiException.notFound("Hostel"));
        Block block = requireBlock(hostelId, blockId);
        String number = form.number().trim();
        if (rooms.existsByBlockIdAndNumberIgnoreCase(blockId, number)) {
            throw new ApiException(HttpStatus.CONFLICT, "Room exists", block.getName() + " already has room "
                    + number + ".", Map.of("number", "A room with this number exists in the block."));
        }
        int floor = form.floor() == null ? 0 : form.floor();
        if (floor > block.getFloors()) {
            throw ApiException.badRequest(block.getName() + " has " + block.getFloors() + " floors.", "floor");
        }
        Room room = rooms.save(new Room(hostelId, blockId, floor, number, form.capacity(), form.type(),
                form.monthlyFeePaise() == null ? 0 : form.monthlyFeePaise(), Instant.now()));
        audit.record(actor, "hostel_room.created", "hostel_room", room.getId(), details(hostel, block, room));
        rooms.flush();
        return roomViews(List.of(room), List.of(block)).get(0);
    }

    public RoomView updateRoom(UUID hostelId, UUID roomId, RoomForm form, Actor actor) {
        TenantContext.require();
        Hostel hostel = hostels.findById(hostelId).orElseThrow(() -> ApiException.notFound("Hostel"));
        Room room = requireRoom(hostelId, roomId);
        Block block = blocks.findById(room.getBlockId()).orElseThrow(() -> ApiException.notFound("Block"));
        String number = form.number().trim();
        if (!room.getNumber().equalsIgnoreCase(number)
                && rooms.existsByBlockIdAndNumberIgnoreCase(room.getBlockId(), number)) {
            throw new ApiException(HttpStatus.CONFLICT, "Room exists", block.getName() + " already has room "
                    + number + ".", Map.of("number", "A room with this number exists in the block."));
        }
        long occupied = allotments.countActiveInRoom(roomId);
        if (form.capacity() < occupied) {
            throw ApiException.badRequest("The room has " + occupied + " boarders; the capacity cannot be lower.",
                    "capacity");
        }
        room.update(form.floor() == null ? room.getFloor() : form.floor(), number, form.capacity(), form.type(),
                form.monthlyFeePaise() == null ? room.getMonthlyFeePaise() : form.monthlyFeePaise(),
                form.active() == null || form.active());
        audit.record(actor, "hostel_room.updated", "hostel_room", roomId, details(hostel, block, room));
        rooms.flush();
        return roomViews(List.of(room), List.of(block)).get(0);
    }

    public void removeRoom(UUID hostelId, UUID roomId, Actor actor) {
        TenantContext.require();
        Hostel hostel = hostels.findById(hostelId).orElseThrow(() -> ApiException.notFound("Hostel"));
        Room room = requireRoom(hostelId, roomId);
        if (allotments.existsByRoomId(roomId)) {
            throw new ApiException(HttpStatus.CONFLICT, "Room was allotted",
                    "Room " + room.getNumber() + " has allotment history; mark it inactive instead.");
        }
        beds.deleteAll(beds.findByRoomIdOrderByLabelAsc(roomId));
        rooms.delete(room);
        audit.record(actor, "hostel_room.removed", "hostel_room", roomId, Map.of("hostel", hostel.getName(),
                "room", room.getNumber()));
        rooms.flush();
    }

    /* ------------------------------------------------------------------ beds */

    public List<BedView> addBeds(UUID hostelId, UUID roomId, BedsForm form, Actor actor) {
        TenantContext.require();
        Hostel hostel = hostels.findById(hostelId).orElseThrow(() -> ApiException.notFound("Hostel"));
        Room room = requireRoom(hostelId, roomId);
        List<String> labels = form.labels().stream().map(String::trim).toList();
        if (labels.stream().map(l -> l.toLowerCase(java.util.Locale.ROOT)).distinct().count() < labels.size()) {
            throw ApiException.badRequest("Each bed label once.", "labels");
        }
        long have = beds.countByRoomId(roomId);
        if (have + labels.size() > room.getCapacity()) {
            throw ApiException.badRequest("Room " + room.getNumber() + " has capacity " + room.getCapacity()
                    + " and already " + have + (have == 1 ? " bed." : " beds."), "labels");
        }
        for (String label : labels) {
            if (beds.existsByRoomIdAndLabelIgnoreCase(roomId, label)) {
                throw new ApiException(HttpStatus.CONFLICT, "Bed exists", "Room " + room.getNumber()
                        + " already has bed " + label + ".", Map.of("labels", "Bed " + label + " exists."));
            }
        }
        Instant now = Instant.now();
        List<Bed> added = new ArrayList<>();
        for (String label : labels) {
            added.add(beds.save(new Bed(hostelId, roomId, label, now)));
        }
        audit.record(actor, "hostel_bed.added", "hostel_room", roomId, Map.of("hostel", hostel.getName(), "room",
                room.getNumber(), "beds", labels));
        beds.flush();
        return bedViews(added, Map.of());
    }

    public void removeBed(UUID hostelId, UUID bedId, Actor actor) {
        TenantContext.require();
        Hostel hostel = hostels.findById(hostelId).orElseThrow(() -> ApiException.notFound("Hostel"));
        Bed bed = beds.findById(bedId).filter(b -> b.getHostelId().equals(hostelId))
                .orElseThrow(() -> ApiException.notFound("Bed"));
        if (allotments.existsByBedId(bedId)) {
            throw new ApiException(HttpStatus.CONFLICT, "Bed was allotted",
                    "Bed " + bed.getLabel() + " has allotment history and cannot be removed.");
        }
        Room room = rooms.findById(bed.getRoomId()).orElse(null);
        beds.delete(bed);
        audit.record(actor, "hostel_bed.removed", "hostel_room", bed.getRoomId(), Map.of("hostel",
                hostel.getName(), "room", room == null ? "" : room.getNumber(), "bed", bed.getLabel()));
        beds.flush();
    }

    /* ------------------------------------------------------------------ the occupancy board */

    @Transactional(readOnly = true)
    public OccupancyBoard board(UUID hostelId) {
        TenantContext.require();
        Hostel hostel = scope.require(hostelId);
        List<Block> blockRows = blocks.findByHostelIdOrderByNameAsc(hostelId);
        Map<UUID, Block> blockById = blockRows.stream().collect(Collectors.toMap(Block::getId, b -> b));
        List<Allotment> active = allotments.activeInHostel(hostelId);
        Map<UUID, Allotment> byBed = active.stream().collect(Collectors.toMap(Allotment::getBedId, a -> a));
        Map<UUID, List<Bed>> bedsByRoom = beds.findByHostelIdOrderByLabelAsc(hostelId).stream()
                .collect(Collectors.groupingBy(Bed::getRoomId));
        List<RoomCard> cards = new ArrayList<>();
        long totalBeds = 0;
        for (Room room : rooms.findByHostelIdOrderByFloorAscNumberAsc(hostelId)) {
            List<Bed> roomBeds = bedsByRoom.getOrDefault(room.getId(), List.of());
            List<BedView> bedViews = bedViews(roomBeds, byBed);
            int free = (int) bedViews.stream().filter(b -> b.allotmentId() == null).count();
            Block block = blockById.get(room.getBlockId());
            totalBeds += roomBeds.size();
            cards.add(new RoomCard(room.getId(), room.getBlockId(), block == null ? null : block.getName(),
                    room.getFloor(), room.getNumber(), room.getCapacity(), room.getType(), room.getMonthlyFeePaise(),
                    room.isActive(), bedViews, free, complaints.countByRoomIdAndStatusNot(room.getId(),
                            ComplaintStatus.RESOLVED)));
        }
        long occupied = active.size();
        return new OccupancyBoard(hostel.getId(), hostel.getName(), hostel.getType(), totalBeds, occupied,
                totalBeds - occupied, HostelRules.occupancyPercent(occupied, totalBeds), blockViews(blockRows), cards);
    }

    /* ------------------------------------------------------------------ helpers */

    Block requireBlock(UUID hostelId, UUID blockId) {
        return blocks.findById(blockId).filter(b -> b.getHostelId().equals(hostelId))
                .orElseThrow(() -> ApiException.notFound("Block"));
    }

    Room requireRoom(UUID hostelId, UUID roomId) {
        return rooms.findById(roomId).filter(r -> r.getHostelId().equals(hostelId))
                .orElseThrow(() -> ApiException.notFound("Room"));
    }

    List<HostelView> views(Collection<Hostel> rows) {
        Map<UUID, String> wardens = people.staffNames(rows.stream().map(Hostel::getWardenUserId).toList());
        boolean manage = HostelAuth.canManage();
        List<HostelView> result = new ArrayList<>();
        for (Hostel h : rows) {
            long bedCount = beds.countByHostelId(h.getId());
            long occupied = allotments.countActiveInHostel(h.getId());
            result.add(new HostelView(h.getId(), h.getName(), h.getType(), h.getWardenUserId(),
                    wardens.get(h.getWardenUserId()), h.getAddress(), h.isActive(), blocks.countByHostelId(h.getId()),
                    rooms.findByHostelIdOrderByFloorAscNumberAsc(h.getId()).size(), bedCount, occupied,
                    HostelRules.occupancyPercent(occupied, bedCount), manage, scope.canWarden(h)));
        }
        return result;
    }

    List<BlockView> blockViews(Collection<Block> rows) {
        return rows.stream().map(b -> new BlockView(b.getId(), b.getHostelId(), b.getName(), b.getFloors(),
                rooms.countByBlockId(b.getId()))).toList();
    }

    List<RoomView> roomViews(Collection<Room> rows, Collection<Block> blockRows) {
        Map<UUID, String> blockNames = blockRows.stream().collect(Collectors.toMap(Block::getId, Block::getName));
        return rows.stream().map(r -> new RoomView(r.getId(), r.getHostelId(), r.getBlockId(),
                blockNames.get(r.getBlockId()), r.getFloor(), r.getNumber(), r.getCapacity(), r.getType(),
                r.getMonthlyFeePaise(), r.isActive(), beds.countByRoomId(r.getId()),
                allotments.countActiveInRoom(r.getId()))).toList();
    }

    private List<BedView> bedViews(Collection<Bed> rows, Map<UUID, Allotment> activeByBed) {
        Set<UUID> studentIds = rows.stream().map(b -> activeByBed.get(b.getId())).filter(a -> a != null)
                .map(Allotment::getStudentId).collect(Collectors.toSet());
        Map<UUID, Pupil> pupils = studentIds.isEmpty() ? new HashMap<>() : people.pupils(studentIds);
        List<BedView> result = new ArrayList<>();
        for (Bed b : rows) {
            Allotment a = activeByBed.get(b.getId());
            Pupil p = a == null ? null : pupils.get(a.getStudentId());
            result.add(new BedView(b.getId(), b.getLabel(), a == null ? null : a.getId(),
                    a == null ? null : a.getStudentId(), p == null ? null : p.fullName(),
                    p == null ? null : p.className(), a == null ? null : a.getFromDate()));
        }
        return result;
    }

    private static Map<String, Object> details(Hostel h, StaffRef warden) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", h.getName());
        d.put("type", h.getType().name());
        d.put("warden", warden == null ? "" : warden.name());
        d.put("active", h.isActive());
        return d;
    }

    private static Map<String, Object> details(Hostel h, Block b, Room r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("hostel", h.getName());
        d.put("block", b.getName());
        d.put("room", r.getNumber());
        d.put("floor", r.getFloor());
        d.put("capacity", r.getCapacity());
        d.put("type", r.getType().name());
        d.put("monthlyFeePaise", r.getMonthlyFeePaise());
        d.put("active", r.isActive());
        return d;
    }
}
