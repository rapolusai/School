package com.akshara.onboarding;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.stream.Collectors;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.accounts.AccountsViews.VendorView;
import com.akshara.accounts.VendorService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.inventory.AssetCondition;
import com.akshara.inventory.AssetService;
import com.akshara.inventory.InventoryForms.ApproverInput;
import com.akshara.inventory.InventoryForms.ApproversForm;
import com.akshara.inventory.InventoryForms.AssetForm;
import com.akshara.inventory.InventoryForms.CategoryForm;
import com.akshara.inventory.InventoryForms.IssueForm;
import com.akshara.inventory.InventoryForms.ItemForm;
import com.akshara.inventory.InventoryForms.LocationForm;
import com.akshara.inventory.InventoryForms.OrderLineInput;
import com.akshara.inventory.InventoryForms.PurchaseOrderForm;
import com.akshara.inventory.InventoryForms.ReceiptForm;
import com.akshara.inventory.InventoryForms.ReceiveForm;
import com.akshara.inventory.InventoryForms.ReceiveLineInput;
import com.akshara.inventory.InventoryForms.RequisitionForm;
import com.akshara.inventory.InventoryForms.RequisitionLineInput;
import com.akshara.inventory.InventorySetupService;
import com.akshara.inventory.InventoryViews.CategoryView;
import com.akshara.inventory.InventoryViews.ItemView;
import com.akshara.inventory.InventoryViews.LocationView;
import com.akshara.inventory.InventoryViews.OrderView;
import com.akshara.inventory.ItemKind;
import com.akshara.inventory.LocationKind;
import com.akshara.inventory.ProcurementService;
import com.akshara.inventory.StockService;
import com.akshara.payroll.PayStaffService;
import com.akshara.payroll.PayrollForms.PayProfileForm;
import com.akshara.payroll.PayrollForms.StructureForm;
import com.akshara.payroll.PayrollViews.ComponentView;
import com.akshara.payroll.SalaryComponentService;
import com.akshara.shared.TenantContext;
import com.akshara.staff.DepartmentService;
import com.akshara.staff.EmploymentType;
import com.akshara.staff.StaffForms.CreateStaff;
import com.akshara.staff.StaffPayrollInputs;
import com.akshara.staff.StaffPayrollInputs.PayrollPerson;
import com.akshara.staff.StaffService;

/**
 * The demo school's stores: a storekeeper (Mohan Das, {@code stores@demo.akshara.test}), categories with
 * depreciation rates, locations, about twenty items with two months of receipts and issues, fifteen assets
 * (projectors, lab equipment, furniture) with custodians, two requisitions waiting for approval, one purchase order
 * waiting for the principal and one received. Vendors are the accounts demo's. Dates follow today.
 */
@Component
class DemoInventoryData {

    private static final Logger log = LoggerFactory.getLogger(DemoInventoryData.class);

    static final String STOREKEEPER_EMAIL = "stores" + DemoDataSeeder.DEMO_DOMAIN;
    static final String STOREKEEPER_NAME = "Mohan Das";

    /** name | depreciation rate in basis points. */
    static final List<String> CATEGORIES = List.of("Stationery|0", "Cleaning|0", "Lab consumables|0",
            "Sports|2000", "Electronics|1500", "Furniture|1000", "Lab equipment|1500");

    /** name | kind. */
    static final List<String> LOCATIONS = List.of("Main store|STORE", "Science lab|LAB", "Library|LIBRARY",
            "Office|OFFICE", "Block A|CLASSROOM_BLOCK", "Block B|CLASSROOM_BLOCK");

    /** name | category | unit | kind | reorder level | preferred vendor | opening quantity | unit cost in rupees. */
    static final List<String> ITEMS = List.of(
            "A4 paper (ream)|Stationery|ream|CONSUMABLE|20|Vijaya Stationers|60|260",
            "Whiteboard markers|Stationery|pc|CONSUMABLE|50|Vijaya Stationers|200|25",
            "Chalk (box)|Stationery|box|CONSUMABLE|10|Vijaya Stationers|40|45",
            "Exam answer booklets|Stationery|pc|CONSUMABLE|500|Vijaya Stationers|2000|4",
            "Registers|Stationery|pc|CONSUMABLE|20|Vijaya Stationers|50|80",
            "Printer toner|Stationery|pc|CONSUMABLE|2|Vijaya Stationers|4|3200",
            "Chart paper|Stationery|sheet|CONSUMABLE|100|Vijaya Stationers|300|8",
            "Phenyl (5 L)|Cleaning|can|CONSUMABLE|5|Hindustan Pest Control|12|380",
            "Hand wash (5 L)|Cleaning|can|CONSUMABLE|4|Hindustan Pest Control|10|420",
            "Dustbin liners|Cleaning|roll|CONSUMABLE|10|Hindustan Pest Control|30|90",
            "Mops|Cleaning|pc|CONSUMABLE|4|Hindustan Pest Control|8|150",
            "Test tubes|Lab consumables|pc|CONSUMABLE|50|Sharma Electricals|120|18",
            "Litmus paper|Lab consumables|book|CONSUMABLE|10|Sharma Electricals|25|35",
            "Dilute HCl (500 ml)|Lab consumables|bottle|CONSUMABLE|5|Sharma Electricals|8|220",
            "Safety goggles|Lab consumables|pc|CONSUMABLE|10|Sharma Electricals|30|160",
            "Footballs|Sports|pc|CONSUMABLE|4|Vijaya Stationers|10|650",
            "Shuttlecocks (tube)|Sports|tube|CONSUMABLE|5|Vijaya Stationers|12|480",
            "Skipping ropes|Sports|pc|CONSUMABLE|5|Vijaya Stationers|15|120",
            "Projector|Electronics|pc|ASSET|0|Sharma Electricals|0|0",
            "Teacher's desk|Furniture|pc|ASSET|0|Sri Lakshmi Constructions|0|0",
            "Student bench|Furniture|pc|ASSET|0|Sri Lakshmi Constructions|0|0");

    /** item | department | custodian mailbox (blank: none) | months ago | day | quantity | purpose. */
    static final List<String> ISSUES = List.of(
            "A4 paper (ream)|Administration|frontoffice|2|3|10|Office printing",
            "A4 paper (ream)|Primary|teacher|2|9|6|Worksheets",
            "Whiteboard markers|Primary|teacher|2|9|40|Class 5 A",
            "Whiteboard markers|Science|anjali.deshmukh|2|12|30|Science block",
            "Chalk (box)|Primary|teacher|2|15|8|Primary wing",
            "Exam answer booklets|Secondary|rahul.verma|2|20|600|Unit test",
            "Phenyl (5 L)|Administration||2|6|3|Housekeeping",
            "Dustbin liners|Administration||2|6|8|Housekeeping",
            "Test tubes|Science|anjali.deshmukh|2|22|30|Class 9 practicals",
            "Footballs|Primary|teacher|2|25|2|Sports period",
            "A4 paper (ream)|Administration|frontoffice|1|4|12|Report cards",
            "A4 paper (ream)|Science|anjali.deshmukh|1|11|4|Lab records",
            "Whiteboard markers|Languages|fatima.shaikh|1|8|25|Language rooms",
            "Chalk (box)|Primary|teacher|1|10|6|Primary wing",
            "Registers|Administration|frontoffice|1|5|10|Visitor and admission registers",
            "Printer toner|Administration|frontoffice|1|5|1|Front office printer",
            "Phenyl (5 L)|Administration||1|7|3|Housekeeping",
            "Hand wash (5 L)|Administration||1|7|2|Washrooms",
            "Litmus paper|Science|anjali.deshmukh|1|18|8|Class 7 practicals",
            "Safety goggles|Science|anjali.deshmukh|1|18|12|Lab stock",
            "Shuttlecocks (tube)|Primary|teacher|1|21|3|Badminton practice",
            "Chart paper|Languages|fatima.shaikh|1|23|60|Annual day backdrops",
            "Whiteboard markers|Primary|teacher|0|2|20|Class 5 B",
            "A4 paper (ream)|Administration|accounts|0|3|5|Fee receipts");

    /** name | category | item (blank: none) | location | custodian mailbox | months ago bought | cost in rupees | condition. */
    static final List<String> ASSETS = List.of(
            "Epson projector, Class 5 A|Electronics|Projector|Block A|teacher|30|42000|GOOD",
            "Epson projector, Class 5 B|Electronics|Projector|Block A|teacher|30|42000|GOOD",
            "BenQ projector, Science lab|Electronics|Projector|Science lab|anjali.deshmukh|18|48500|GOOD",
            "BenQ projector, Class 9 A|Electronics|Projector|Block B|rahul.verma|18|48500|FAIR",
            "ViewSonic projector, Library|Electronics|Projector|Library|librarian|8|39900|GOOD",
            "Compound microscope (Olympus)|Lab equipment||Science lab|anjali.deshmukh|26|28500|GOOD",
            "Compound microscope (Olympus)|Lab equipment||Science lab|anjali.deshmukh|26|28500|GOOD",
            "Digital weighing balance|Lab equipment||Science lab|anjali.deshmukh|14|12800|GOOD",
            "Bunsen burner set (6)|Lab equipment||Science lab|anjali.deshmukh|40|9600|FAIR",
            "Van de Graaff generator|Lab equipment||Science lab|anjali.deshmukh|52|21000|POOR",
            "Teacher's desk, staff room|Furniture|Teacher's desk|Block A|teacher|60|7200|FAIR",
            "Student benches, Class 5 A (20)|Furniture|Student bench|Block A|teacher|60|64000|FAIR",
            "HP LaserJet printer, front office|Electronics||Office|frontoffice|20|18900|GOOD",
            "Dell desktop, accounts|Electronics||Office|accounts|34|46500|GOOD",
            "Steel almirah, library|Furniture||Library|librarian|48|11500|GOOD");

    private final StaffService staff;
    private final DepartmentService departments;
    private final StaffPayrollInputs people;
    private final VendorService vendors;
    private final InventorySetupService setup;
    private final StockService stock;
    private final AssetService assets;
    private final ProcurementService procurement;
    private final SalaryComponentService components;
    private final PayStaffService payStaff;
    private final TransactionTemplate tx;

    DemoInventoryData(StaffService staff, DepartmentService departments, StaffPayrollInputs people,
            VendorService vendors, InventorySetupService setup, StockService stock, AssetService assets,
            ProcurementService procurement, SalaryComponentService components, PayStaffService payStaff,
            PlatformTransactionManager transactionManager) {
        this.staff = staff;
        this.departments = departments;
        this.people = people;
        this.vendors = vendors;
        this.setup = setup;
        this.stock = stock;
        this.assets = assets;
        this.procurement = procurement;
        this.components = components;
        this.payStaff = payStaff;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Seeds the stores and returns the number of items. {@code passwordHash} is the demo password, already hashed,
     * for the storekeeper.
     */
    int seed(UUID tenantId, Map<String, UUID> userIds, UUID adminId, String passwordHash) {
        Actor admin = new Actor(adminId, "Priya Nair");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        UUID keeperId = TenantContext.runAs(tenantId, () -> tx.execute(status -> storekeeper(admin, passwordHash)));
        Actor keeper = new Actor(keeperId, STOREKEEPER_NAME);
        Actor principal = new Actor(userIds.get("principal" + DemoDataSeeder.DEMO_DOMAIN), "Lakshmi Iyer");
        Actor accountant = new Actor(userIds.get("accounts" + DemoDataSeeder.DEMO_DOMAIN), "Meena Reddy");
        step(tenantId, "the storekeeper's salary", () -> salary(keeperId, accountant));
        Map<String, UUID> ids = new HashMap<>();
        step(tenantId, "approvers", () -> setup.saveApprovers(new ApproversForm(List.of(
                new ApproverInput(principal.id(), 50_000_00L), new ApproverInput(adminId, null))), admin));
        step(tenantId, "masters", () -> masters(keeper, ids));
        step(tenantId, "items", () -> items(keeper, ids, today));
        step(tenantId, "issues", () -> issues(keeper, ids, today));
        step(tenantId, "assets", () -> assets(keeper, ids, today));
        step(tenantId, "requisitions", () -> requisitions(userIds, ids, today));
        step(tenantId, "purchase orders", () -> orders(keeper, principal, ids, today));
        return ITEMS.size();
    }

    private UUID storekeeper(Actor admin, String passwordHash) {
        UUID administration = departments.list().stream().filter(d -> d.name().equals("Administration"))
                .map(d -> d.id()).findFirst().orElse(null);
        return staff.create(new CreateStaff(STOREKEEPER_NAME, STOREKEEPER_EMAIL, null, List.of("STOREKEEPER"),
                "AKS-204", "Storekeeper", administration, EmploymentType.PERMANENT, LocalDate.of(2021, 7, 1),
                "9848010204", "B.Com.", null, null), passwordHash, admin).userId();
    }

    /** Like every other member of staff, the storekeeper has (fake) pay details and a salary structure. */
    private Object salary(UUID keeperId, Actor accountant) {
        Map<String, UUID> ids = components.list().stream()
                .collect(Collectors.toMap(ComponentView::code, ComponentView::id, (a, b) -> a));
        if (!ids.containsKey("BASIC")) {
            return null;
        }
        payStaff.saveProfile(keeperId, new PayProfileForm("000000100204", "AKSH0000001", null, "DEMOP0204A",
                "000000000204", "0000000204", true, false), accountant);
        payStaff.createStructure(keeperId, new StructureForm(YearMonth.now(ZoneId.of("Asia/Kolkata")).toString(),
                "Demo salary", DemoPayrollData.lines(ids, 18_000L)), accountant);
        return null;
    }

    private Object masters(Actor keeper, Map<String, UUID> ids) {
        for (String c : CATEGORIES) {
            String[] p = c.split("\\|");
            CategoryView v = setup.createCategory(new CategoryForm(p[0], Integer.parseInt(p[1]), true), keeper);
            ids.put("cat:" + p[0], v.id());
        }
        for (String l : LOCATIONS) {
            String[] p = l.split("\\|");
            LocationView v = setup.createLocation(new LocationForm(p[0], LocationKind.valueOf(p[1]), true), keeper);
            ids.put("loc:" + p[0], v.id());
        }
        for (VendorView v : vendors.list()) {
            ids.put("vendor:" + v.name(), v.id());
        }
        for (var d : departments.list()) {
            ids.put("dept:" + d.name(), d.id());
        }
        for (PayrollPerson p : people.people()) {
            ids.put("user:" + p.email().substring(0, p.email().indexOf('@')), p.userId());
        }
        return null;
    }

    private Object items(Actor keeper, Map<String, UUID> ids, LocalDate today) {
        LocalDate opening = today.minusMonths(2).withDayOfMonth(1);
        for (String line : ITEMS) {
            String[] p = line.split("\\|");
            ItemView item = stock.create(new ItemForm(p[0], ids.get("cat:" + p[1]), p[2], ItemKind.valueOf(p[3]),
                    Integer.parseInt(p[4]), ids.get("vendor:" + p[5]), true), keeper);
            ids.put("item:" + p[0], item.id());
            int quantity = Integer.parseInt(p[6]);
            if (quantity > 0) {
                stock.receive(new ReceiptForm(item.id(), ids.get("loc:Main store"), quantity,
                        Long.parseLong(p[7]) * 100, opening, "Opening stock"), keeper);
            }
        }
        return null;
    }

    private Object issues(Actor keeper, Map<String, UUID> ids, LocalDate today) {
        for (String line : ISSUES) {
            String[] p = line.split("\\|", -1);
            LocalDate on = dateOf(today, Integer.parseInt(p[3]), Integer.parseInt(p[4]));
            if (on.isAfter(today)) {
                continue;
            }
            stock.issue(new IssueForm(ids.get("item:" + p[0]), ids.get("loc:Main store"), Integer.parseInt(p[5]),
                    on, ids.get("dept:" + p[1]), p[2].isEmpty() ? null : ids.get("user:" + p[2]), p[6]), keeper);
        }
        // A return and a write-off, so the ledger shows every kind.
        stock.giveBack(new IssueForm(ids.get("item:Safety goggles"), ids.get("loc:Main store"), 2,
                today.minusDays(1), ids.get("dept:Science"), ids.get("user:anjali.deshmukh"),
                "Spare pairs returned"), keeper);
        return null;
    }

    private Object assets(Actor keeper, Map<String, UUID> ids, LocalDate today) {
        for (String line : ASSETS) {
            String[] p = line.split("\\|", -1);
            int monthsAgo = Integer.parseInt(p[5]);
            LocalDate bought = today.minusMonths(monthsAgo).withDayOfMonth(Math.min(15, today.lengthOfMonth()));
            assets.create(new AssetForm(p[0], ids.get("cat:" + p[1]), p[2].isEmpty() ? null : ids.get("item:" + p[2]),
                    ids.get("loc:" + p[3]), ids.get("user:" + p[4]), bought, Long.parseLong(p[6]) * 100,
                    p[1].equals("Electronics") ? ids.get("vendor:Sharma Electricals")
                            : ids.get("vendor:Sri Lakshmi Constructions"), null, AssetCondition.valueOf(p[7]),
                    monthsAgo < 24 ? bought.plusYears(2) : null, null), keeper);
        }
        return null;
    }

    private Object requisitions(Map<String, UUID> userIds, Map<String, UUID> ids, LocalDate today) {
        // Ravi (Primary, no department head) asks for sports gear: waiting for the principal.
        Actor ravi = new Actor(userIds.get("teacher" + DemoDataSeeder.DEMO_DOMAIN), "Ravi Kumar");
        procurement.create(new RequisitionForm(ids.get("dept:Primary"), today.plusDays(14),
                "Sports day practice equipment", List.of(
                        new RequisitionLineInput(ids.get("item:Footballs"), "Footballs, size 5", 6, "pc", 650_00L),
                        new RequisitionLineInput(ids.get("item:Skipping ropes"), "Skipping ropes", 20, "pc", 120_00L),
                        new RequisitionLineInput(null, "Relay batons (set of 8)", 2, "set", 900_00L))),
                true, false, ravi);
        // Rahul (Science) asks for lab glassware: waiting for his department head, Anjali.
        Actor rahul = new Actor(ids.get("user:rahul.verma"), "Rahul Verma");
        procurement.create(new RequisitionForm(ids.get("dept:Science"), today.plusDays(21),
                "Class 9 practical examinations", List.of(
                        new RequisitionLineInput(ids.get("item:Test tubes"), "Borosilicate test tubes", 100, "pc",
                                18_00L),
                        new RequisitionLineInput(null, "Conical flasks, 250 ml", 24, "pc", 95_00L),
                        new RequisitionLineInput(null, "Burettes, 50 ml", 12, "pc", 420_00L))),
                true, false, rahul);
        return null;
    }

    private Object orders(Actor keeper, Actor principal, Map<String, UUID> ids, LocalDate today) {
        // Received last month: stationery for the half-yearly examinations.
        OrderView received = procurement.createOrder(new PurchaseOrderForm(ids.get("vendor:Vijaya Stationers"), null,
                null, dateOf(today, 1, 6), dateOf(today, 1, 16), "Delivery to the main store; payment within 30 days.",
                List.of(new OrderLineInput(ids.get("item:A4 paper (ream)"), "A4 paper, 75 GSM (ream)", 40, "ream",
                        260_00L, 1200),
                        new OrderLineInput(ids.get("item:Exam answer booklets"), "Answer booklets, 32 pages", 1500,
                                "pc", 4_00L, 1200),
                        new OrderLineInput(ids.get("item:Printer toner"), "Toner cartridge HP 88A", 2, "pc",
                                3200_00L, 1800))),
                true, keeper);
        procurement.approveOrder(received.id(), null, principal);
        OrderView detail = procurement.order(received.id(), principal.id(), true);
        procurement.receive(received.id(), new ReceiveForm(dateOf(today, 1, 14), ids.get("loc:Main store"),
                "VS/" + today.getYear() + "/1187", "All lines in full.",
                detail.lines().stream().map(l -> new ReceiveLineInput(l.id(), l.quantity())).toList()), keeper);
        // Waiting for the principal: a projector for Class 9 B.
        procurement.createOrder(new PurchaseOrderForm(ids.get("vendor:Sharma Electricals"), null, null,
                today.minusDays(2), today.plusDays(12), "Installation and two-year warranty included.",
                List.of(new OrderLineInput(ids.get("item:Projector"), "BenQ MX560 projector", 1, "pc", 41_500_00L,
                        1800),
                        new OrderLineInput(null, "Ceiling mount and 10 m HDMI cable", 1, "set", 2_400_00L, 1800))),
                true, keeper);
        return null;
    }

    /** The given day of the month that many months ago (clamped to the month's length). */
    static LocalDate dateOf(LocalDate today, int monthsAgo, int day) {
        LocalDate month = today.minusMonths(monthsAgo).withDayOfMonth(1);
        return month.withDayOfMonth(Math.min(day, month.lengthOfMonth()));
    }

    private void step(UUID tenantId, String what, Supplier<Object> work) {
        try {
            TenantContext.runAs(tenantId, () -> tx.execute(status -> work.get()));
        } catch (RuntimeException e) {
            log.info("Skipped demo inventory step '{}' ({})", what, e.getMessage());
        }
    }
}
