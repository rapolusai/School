package com.akshara.onboarding;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService.Actor;
import com.akshara.hostel.AllotmentService;
import com.akshara.hostel.ComplaintCategory;
import com.akshara.hostel.ComplaintService;
import com.akshara.hostel.HostelForms.AllotForm;
import com.akshara.hostel.HostelForms.BedsForm;
import com.akshara.hostel.HostelForms.BlockForm;
import com.akshara.hostel.HostelForms.ComplaintForm;
import com.akshara.hostel.HostelForms.HostelForm;
import com.akshara.hostel.HostelForms.MealCount;
import com.akshara.hostel.HostelForms.MenuEntry;
import com.akshara.hostel.HostelForms.MenuForm;
import com.akshara.hostel.HostelForms.MessAttendanceForm;
import com.akshara.hostel.HostelForms.PassRequest;
import com.akshara.hostel.HostelForms.RollCallForm;
import com.akshara.hostel.HostelForms.RollCallMark;
import com.akshara.hostel.HostelForms.RoomForm;
import com.akshara.hostel.HostelPassService;
import com.akshara.hostel.HostelSetupService;
import com.akshara.hostel.HostelText;
import com.akshara.hostel.HostelType;
import com.akshara.hostel.HostelViews.BedView;
import com.akshara.hostel.HostelViews.BlockView;
import com.akshara.hostel.HostelViews.HostelView;
import com.akshara.hostel.HostelViews.RoomView;
import com.akshara.hostel.Meal;
import com.akshara.hostel.MessService;
import com.akshara.hostel.PassKind;
import com.akshara.hostel.RollCallService;
import com.akshara.hostel.RollCallStatus;
import com.akshara.hostel.RoomType;
import com.akshara.shared.JwtService;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;
import com.akshara.staff.DepartmentService;
import com.akshara.staff.EmploymentType;
import com.akshara.staff.StaffForms.CreateStaff;
import com.akshara.staff.StaffService;
import com.akshara.students.Gender;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

/**
 * The demo school's hostels: a boys' and a girls' hostel with a warden (Vikram Pillai, {@code
 * warden@demo.akshara.test}, warden of both), rooms and beds, about twelve boarders including Arjun Sharma (whose
 * mother signs in to the family app), this week's menu, today's mess counts, last night's roll call with one
 * absentee (so an alert and a simulated message exist), a pending outing pass from Arjun's mother and one open
 * complaint. Dates follow today.
 */
@Component
class DemoHostelData {

    private static final Logger log = LoggerFactory.getLogger(DemoHostelData.class);

    static final String WARDEN_EMAIL = "warden" + DemoDataSeeder.DEMO_DOMAIN;
    static final String WARDEN_NAME = "Vikram Pillai";
    static final String ARJUN = "Arjun Sharma";
    private static final int BOARDERS = 12;
    private static final List<String> ADMIN = List.of(Permissions.HOSTEL_MANAGE, Permissions.HOSTEL_READ);
    private static final List<String> WARDEN = List.of(Permissions.HOSTEL_WARDEN);
    private static final List<String> PARENT = List.of(Permissions.CHILD_VIEW);

    private final StaffService staff;
    private final DepartmentService departments;
    private final StudentService students;
    private final HostelSetupService setup;
    private final AllotmentService allotments;
    private final MessService mess;
    private final HostelPassService passes;
    private final RollCallService rollCall;
    private final ComplaintService complaints;
    private final TransactionTemplate tx;

    DemoHostelData(StaffService staff, DepartmentService departments, StudentService students,
            HostelSetupService setup, AllotmentService allotments, MessService mess, HostelPassService passes,
            RollCallService rollCall, ComplaintService complaints, PlatformTransactionManager transactionManager) {
        this.staff = staff;
        this.departments = departments;
        this.students = students;
        this.setup = setup;
        this.allotments = allotments;
        this.mess = mess;
        this.passes = passes;
        this.rollCall = rollCall;
        this.complaints = complaints;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Returns how many boarders were allotted. {@code passwordHash} is the demo password for the warden. */
    int seed(UUID tenantId, Map<String, UUID> userIds, UUID adminId, String passwordHash) {
        Actor admin = new Actor(adminId, "Priya Nair");
        UUID parentId = userIds.get("parent" + DemoDataSeeder.DEMO_DOMAIN);
        UUID wardenId;
        try {
            wardenId = TenantContext.runAs(tenantId, () -> tx.execute(status -> warden(admin, passwordHash)));
        } catch (RuntimeException e) {
            log.warn("Skipped demo hostel data: the warden could not be created: {}", e.getMessage());
            return 0;
        }
        Actor warden = new Actor(wardenId, WARDEN_NAME);
        List<UUID> boys = new ArrayList<>();
        List<UUID> girls = new ArrayList<>();
        int count = 0;
        try {
            count = TenantContext.runAs(tenantId, () -> tx.execute(status -> as(admin, ADMIN, () -> {
                UUID boysHostel = hostel("Boys Hostel", HostelType.BOYS, wardenId, "Behind the main building",
                        new String[] {"Ground floor", "First floor"}, admin, boys);
                UUID girlsHostel = hostel("Girls Hostel", HostelType.GIRLS, wardenId, "Next to the library",
                        new String[] {"East wing"}, admin, girls);
                menu(boysHostel, warden);
                menu(girlsHostel, warden);
                return boys.size() + girls.size();
            })));
        } catch (RuntimeException e) {
            log.warn("Skipped demo hostel data: {}", e.getMessage());
            return 0;
        }
        step(tenantId, "the mess counts", () -> as(warden, WARDEN, () -> messCounts(warden, boys.size(),
                girls.size())));
        step(tenantId, "last night's roll call", () -> as(warden, WARDEN, () -> rollCalls(warden, boys, girls)));
        if (parentId != null) {
            Actor parent = new Actor(parentId, "Anitha Sharma");
            step(tenantId, "Arjun's outing pass", () -> as(parent, PARENT, () -> outing(parent)));
        }
        step(tenantId, "a complaint", () -> as(warden, WARDEN, () -> complaint(warden)));
        return count;
    }

    private UUID warden(Actor admin, String passwordHash) {
        UUID administration = departments.list().stream().filter(d -> d.name().equals("Administration"))
                .map(d -> d.id()).findFirst().orElse(null);
        return staff.create(new CreateStaff(WARDEN_NAME, WARDEN_EMAIL, null, List.of("WARDEN"), "AKS-701",
                "Hostel Warden", administration, EmploymentType.PERMANENT, LocalDate.of(2021, 6, 1), "9848030401",
                null, null, null), passwordHash, admin).userId();
    }

    /** A hostel with its blocks, rooms and beds; allots the pupils and collects their ids. */
    private UUID hostel(String name, HostelType type, UUID wardenId, String address, String[] blockNames,
            Actor admin, List<UUID> boarders) {
        HostelView hostel = setup.create(new HostelForm(name, type, wardenId, address, true), admin);
        List<UUID> beds = new ArrayList<>();
        int number = 100;
        for (String blockName : blockNames) {
            BlockView block = setup.addBlock(hostel.id(), new BlockForm(blockName, 1), admin);
            for (int i = 1; i <= 3; i++) {
                number++;
                RoomType roomType = i == 3 ? RoomType.TRIPLE : RoomType.DOUBLE;
                int capacity = roomType == RoomType.TRIPLE ? 3 : 2;
                RoomView room = setup.addRoom(hostel.id(), block.id(), new RoomForm(0, String.valueOf(number),
                        capacity, roomType, roomType == RoomType.TRIPLE ? 350_000L : 450_000L, true), admin);
                List<String> labels = capacity == 3 ? List.of("A", "B", "C") : List.of("A", "B");
                for (BedView bed : setup.addBeds(hostel.id(), room.id(), new BedsForm(labels), admin)) {
                    beds.add(bed.id());
                }
            }
        }
        List<UUID> pupils = pupils(type == HostelType.BOYS ? Gender.MALE : Gender.FEMALE,
                type == HostelType.BOYS ? 7 : BOARDERS - 7);
        LocalDate from = HostelText.today().withDayOfMonth(1).minusMonths(2);
        for (int i = 0; i < pupils.size() && i < beds.size() - 1; i++) {
            allotments.allot(new AllotForm(pupils.get(i), beds.get(i), from, true), admin);
            boarders.add(pupils.get(i));
        }
        return hostel.id();
    }

    /** Active students of the gender, Arjun Sharma first among the boys. */
    private List<UUID> pupils(Gender gender, int wanted) {
        List<StudentRow> rows = students.list(new StudentQuery(null, null, null, StudentStatus.ACTIVE, null, 0, 100))
                .items();
        List<UUID> result = new ArrayList<>();
        rows.stream().filter(r -> r.fullName().equals(ARJUN) && gender == Gender.MALE).map(StudentRow::id)
                .findFirst().ifPresent(result::add);
        for (StudentRow r : rows) {
            if (result.size() >= wanted) {
                break;
            }
            if (r.gender() == gender && !result.contains(r.id()) && r.className() != null) {
                result.add(r.id());
            }
        }
        return result;
    }

    private void menu(UUID hostelId, Actor warden) {
        String[][] week = {
            {"Idli, sambar, chutney", "Rice, dal, bhindi fry, curd", "Tea, biscuits", "Chapati, paneer butter masala"},
            {"Poha, banana", "Rice, sambar, cabbage poriyal", "Tea, samosa", "Chicken curry, rice, salad"},
            {"Upma, coconut chutney", "Rajma chawal, salad", "Milk, murukku", "Chapati, aloo gobi, dal"},
            {"Paratha, curd, pickle", "Rice, rasam, beans fry, papad", "Tea, vada", "Egg curry, rice"},
            {"Dosa, sambar", "Chole, rice, raita", "Juice, cake", "Chapati, mixed vegetable, kheer"},
            {"Puri, chana masala", "Vegetable biryani, raita", "Tea, bhel", "Fish curry, rice"},
            {"Bread, butter, jam, eggs", "Rice, dal tadka, palak paneer", "Milk, laddoo", "Chapati, dal, sabzi"},
        };
        List<MenuEntry> items = new ArrayList<>();
        Meal[] meals = Meal.values();
        for (int day = 0; day < 7; day++) {
            for (int m = 0; m < meals.length; m++) {
                String dish = week[day][m];
                boolean veg = !(dish.startsWith("Chicken") || dish.startsWith("Egg") || dish.startsWith("Fish")
                        || dish.contains("eggs"));
                items.add(new MenuEntry(day + 1, meals[m], dish, veg));
            }
        }
        mess.saveMenu(hostelId, new MenuForm(items), warden);
    }

    private Object messCounts(Actor warden, int boys, int girls) {
        LocalDate today = HostelText.today();
        for (HostelView h : setup.list()) {
            int boarders = h.type() == HostelType.BOYS ? boys : girls;
            mess.mark(h.id(), today, new MessAttendanceForm(List.of(new MealCount(Meal.BREAKFAST,
                    Math.max(boarders - 1, 0)), new MealCount(Meal.LUNCH, boarders))), warden);
        }
        return null;
    }

    /** Last night's roll call of both hostels: everyone present but the last boy, who was absent. */
    private Object rollCalls(Actor warden, List<UUID> boys, List<UUID> girls) {
        LocalDate lastNight = HostelText.today().minusDays(1);
        for (HostelView h : setup.list()) {
            List<UUID> boarders = h.type() == HostelType.BOYS ? boys : girls;
            List<RollCallMark> marks = new ArrayList<>();
            for (int i = 0; i < boarders.size(); i++) {
                boolean absent = h.type() == HostelType.BOYS && i == boarders.size() - 1;
                marks.add(new RollCallMark(boarders.get(i), absent ? RollCallStatus.ABSENT : RollCallStatus.PRESENT,
                        absent ? "Not in the room at 10 pm; phone switched off" : null));
            }
            if (!marks.isEmpty()) {
                rollCall.take(h.id(), lastNight, new RollCallForm(marks), warden);
            }
        }
        return null;
    }

    private Object outing(Actor parent) {
        UUID arjun = students.list(new StudentQuery(null, null, null, StudentStatus.ACTIVE, ARJUN, 0, 5)).items()
                .stream().filter(r -> r.fullName().equals(ARJUN)).map(StudentRow::id).findFirst().orElse(null);
        if (arjun == null) {
            return null;
        }
        passes.request(parent.id(), arjun, new PassRequest(PassKind.OUTING, HostelText.today().plusDays(2), null,
                "Cousin's birthday lunch at home"), parent);
        return null;
    }

    private Object complaint(Actor warden) {
        for (HostelView h : setup.list()) {
            if (h.type() == HostelType.BOYS) {
                List<RoomView> rooms = setup.detail(h.id()).rooms();
                if (!rooms.isEmpty()) {
                    complaints.raise(new ComplaintForm(rooms.get(0).id(), ComplaintCategory.PLUMBING,
                            "The bathroom tap keeps dripping through the night."), warden);
                }
            }
        }
        return null;
    }

    /** Runs the work signed in as the person, with the permissions they act with here. */
    private static <T> T as(Actor actor, List<String> permissions, Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("demo-data").header("alg", "none").subject(actor.id().toString())
                .claim(JwtService.CLAIM_NAME, actor.name()).issuedAt(now).expiresAt(now.plusSeconds(600)).build();
        context.setAuthentication(new JwtAuthenticationToken(jwt, permissions.stream()
                .map(SimpleGrantedAuthority::new).toList()));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /** Runs one piece of demo data in its own transaction; a piece that cannot be seeded is logged and skipped. */
    private boolean step(UUID tenantId, String what, Supplier<Object> work) {
        try {
            TenantContext.runAs(tenantId, () -> tx.execute(status -> work.get()));
            return true;
        } catch (RuntimeException e) {
            log.warn("Skipped demo hostel data for {}: {}", what, e.getMessage());
            return false;
        }
    }
}
