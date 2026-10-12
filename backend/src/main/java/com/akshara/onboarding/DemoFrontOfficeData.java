package com.akshara.onboarding;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.time.YearMonth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService.Actor;
import com.akshara.frontoffice.CallDirection;
import com.akshara.frontoffice.CollectorKind;
import com.akshara.frontoffice.CourierDirection;
import com.akshara.frontoffice.CourierService;
import com.akshara.frontoffice.EnquiryService;
import com.akshara.frontoffice.EnquirySource;
import com.akshara.frontoffice.EnquiryTopic;
import com.akshara.frontoffice.FrontOfficeForms.BlockForm;
import com.akshara.frontoffice.FrontOfficeForms.CallForm;
import com.akshara.frontoffice.FrontOfficeForms.CourierForm;
import com.akshara.frontoffice.FrontOfficeForms.DecisionForm;
import com.akshara.frontoffice.FrontOfficeForms.EnquiryForm;
import com.akshara.frontoffice.FrontOfficeForms.OfficePassForm;
import com.akshara.frontoffice.FrontOfficeForms.PickupPersonForm;
import com.akshara.frontoffice.FrontOfficeForms.VisitorForm;
import com.akshara.frontoffice.FrontOfficeText;
import com.akshara.frontoffice.FrontOfficeViews.GatePassView;
import com.akshara.frontoffice.FrontOfficeViews.VisitorView;
import com.akshara.frontoffice.GatePassService;
import com.akshara.frontoffice.IdType;
import com.akshara.frontoffice.PickupPersonService;
import com.akshara.frontoffice.VisitorPurpose;
import com.akshara.frontoffice.VisitorService;
import com.akshara.payroll.PayStaffService;
import com.akshara.payroll.PayrollForms.PayProfileForm;
import com.akshara.payroll.PayrollForms.StructureForm;
import com.akshara.payroll.PayrollViews.ComponentView;
import com.akshara.payroll.SalaryComponentService;
import com.akshara.shared.TenantContext;
import com.akshara.staff.DepartmentService;
import com.akshara.staff.EmploymentType;
import com.akshara.staff.StaffForms.CreateStaff;
import com.akshara.staff.StaffService;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

/**
 * The demo school's front office: a security guard (Ramesh Yadav, {@code security@demo.akshara.test}), today's
 * visitors (two checked in, one already out), an approved early pickup pass for Arjun Sharma waiting at the gate
 * (the OTP is in the simulated message log), his mother's authorised pickup person, a few enquiries and calls with one
 * follow-up due today, a blocklist entry and two courier entries. Dates follow today.
 */
@Component
class DemoFrontOfficeData {

    private static final Logger log = LoggerFactory.getLogger(DemoFrontOfficeData.class);

    static final String SECURITY_EMAIL = "security" + DemoDataSeeder.DEMO_DOMAIN;
    static final String SECURITY_NAME = "Ramesh Yadav";
    static final String ARJUN = "Arjun Sharma";

    private final StaffService staff;
    private final DepartmentService departments;
    private final StudentService students;
    private final VisitorService visitors;
    private final GatePassService gatePasses;
    private final PickupPersonService pickupPersons;
    private final EnquiryService enquiries;
    private final CourierService courier;
    private final SalaryComponentService components;
    private final PayStaffService payStaff;
    private final TransactionTemplate tx;

    DemoFrontOfficeData(StaffService staff, DepartmentService departments, StudentService students,
            VisitorService visitors, GatePassService gatePasses, PickupPersonService pickupPersons,
            EnquiryService enquiries, CourierService courier, SalaryComponentService components,
            PayStaffService payStaff, PlatformTransactionManager transactionManager) {
        this.staff = staff;
        this.departments = departments;
        this.students = students;
        this.visitors = visitors;
        this.gatePasses = gatePasses;
        this.pickupPersons = pickupPersons;
        this.enquiries = enquiries;
        this.courier = courier;
        this.components = components;
        this.payStaff = payStaff;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Returns how many visitors were recorded. {@code passwordHash} is the demo password for the security user. */
    int seed(UUID tenantId, Map<String, UUID> userIds, UUID adminId, String passwordHash) {
        Actor admin = new Actor(adminId, "Priya Nair");
        UUID officeId = userIds.get("frontoffice" + DemoDataSeeder.DEMO_DOMAIN);
        UUID parentId = userIds.get("parent" + DemoDataSeeder.DEMO_DOMAIN);
        UUID teacherId = userIds.get("teacher" + DemoDataSeeder.DEMO_DOMAIN);
        UUID principalId = userIds.get("principal" + DemoDataSeeder.DEMO_DOMAIN);
        if (officeId == null || parentId == null) {
            return 0;
        }
        Actor office = new Actor(officeId, "Suresh Rao");
        Actor parent = new Actor(parentId, "Anitha Sharma");
        UUID securityId = TenantContext.runAs(tenantId, () -> tx.execute(status -> security(admin, passwordHash)));
        Actor security = new Actor(securityId, SECURITY_NAME);
        UUID accountsId = userIds.get("accounts" + DemoDataSeeder.DEMO_DOMAIN);
        if (accountsId != null) {
            step(tenantId, "the guard's salary", () -> salary(securityId, new Actor(accountsId, "Meena Reddy")));
        }
        LocalDate today = FrontOfficeText.today();
        Instant now = Instant.now();

        int count = 0;
        count += step(tenantId, "visitors", () -> visitors(office, security, teacherId, principalId, now)) ? 3 : 0;
        step(tenantId, "the blocklist", () -> visitors.block(new BlockForm("Unknown salesman", "9800000099",
                "Kept returning to sell insurance to staff during school hours."), office));
        step(tenantId, "Arjun's pickup pass", () -> pass(office, parent, now));
        step(tenantId, "enquiries and calls", () -> enquiriesAndCalls(office, officeId, principalId, today, now));
        step(tenantId, "the courier register", () -> courierEntries(office, today));
        return count;
    }

    private UUID security(Actor admin, String passwordHash) {
        UUID administration = departments.list().stream().filter(d -> d.name().equals("Administration"))
                .map(d -> d.id()).findFirst().orElse(null);
        return staff.create(new CreateStaff(SECURITY_NAME, SECURITY_EMAIL, null, List.of("SECURITY"), "AKS-501",
                "Security Guard", administration, EmploymentType.CONTRACT, LocalDate.of(2022, 4, 1), "9848030301",
                null, null, null), passwordHash, admin).userId();
    }

    /** Like every other member of staff, the guard has (fake) pay details and a salary structure. */
    private Object salary(UUID securityId, Actor accountant) {
        Map<String, UUID> ids = components.list().stream()
                .collect(Collectors.toMap(ComponentView::code, ComponentView::id, (a, b) -> a));
        if (!ids.containsKey("BASIC")) {
            return null;
        }
        payStaff.saveProfile(securityId, new PayProfileForm("000000100501", "AKSH0000001", null, "DEMOP0501A",
                "000000000501", "0000000501", true, false), accountant);
        payStaff.createStructure(securityId, new StructureForm(YearMonth.now(FrontOfficeText.INDIA).toString(),
                "Demo salary", DemoPayrollData.lines(ids, 15_000L)), accountant);
        return null;
    }

    private Object visitors(Actor office, Actor security, UUID teacherId, UUID principalId, Instant now) {
        VisitorView out = visitors.add(new VisitorForm("Prakash Nair", "9876511001", VisitorPurpose.DELIVERY,
                "Stationery delivery for the office", null, "Front office", 1, IdType.COMPANY_ID, null, null, null),
                security);
        visitors.checkOut(out.id(), security);
        visitors.add(new VisitorForm("Sunita Deshpande", "9876511002", VisitorPurpose.MEETING,
                "About her son's admission", principalId, null, 2, IdType.AADHAAR, null, null, null), security);
        visitors.add(new VisitorForm("Mohan Kulkarni", "9876511003", VisitorPurpose.VENDOR,
                "Water cooler service", teacherId, null, 1, IdType.DRIVING_LICENCE, null, null, null), office);
        visitors.add(new VisitorForm("Dr. Meera Joshi", null, VisitorPurpose.OFFICIAL,
                "Annual health check-up camp", principalId, null, 3, IdType.NONE, null,
                FrontOfficeText.today().plusDays(2), null), office);
        return null;
    }

    private Object pass(Actor office, Actor parent, Instant now) {
        UUID arjun = pupil(ARJUN);
        if (arjun == null) {
            return null;
        }
        pickupPersons.add(parent.id(), arjun, new PickupPersonForm("Rajesh Sharma", "Father", "9876500002"), parent);
        pickupPersons.add(parent.id(), arjun, new PickupPersonForm("Kamla Devi", "Grandmother", "9876500003"),
                parent);
        Instant pickupAt = now.plus(Duration.ofMinutes(90));
        if (!FrontOfficeText.dayOf(pickupAt).equals(FrontOfficeText.today())) {
            pickupAt = now.plusSeconds(60);
        }
        GatePassView pass = gatePasses.raise(new OfficePassForm(arjun, pickupAt, "Dentist appointment at 3 pm",
                CollectorKind.OTHER, "Rajesh Sharma", "9876500002"), office);
        gatePasses.approve(pass.id(), new DecisionForm("Mother called the office"), office);
        return null;
    }

    private Object enquiriesAndCalls(Actor office, UUID officeId, UUID principalId, LocalDate today, Instant now) {
        enquiries.logEnquiry(new EnquiryForm(EnquirySource.WALK_IN, EnquiryTopic.ADMISSION, "Kavita Menon",
                "9876512001", "Aarav Menon", "Class 1", "Asked about admission for Class 1 next year, fee structure "
                        + "and the transport routes.", today, officeId, now.minus(Duration.ofHours(3))), office);
        enquiries.logEnquiry(new EnquiryForm(EnquirySource.PHONE, EnquiryTopic.FEES, "Rajiv Bhatia", "9876512002",
                null, null, "Wanted to know whether the second term fee can be paid in two parts.",
                today.plusDays(2), officeId, now.minus(Duration.ofHours(2))), office);
        var closed = enquiries.logEnquiry(new EnquiryForm(EnquirySource.PHONE, EnquiryTopic.GENERAL, "Shalini Rao",
                "9876512003", null, null, "Asked for the school's timings on Saturdays.", null, null,
                now.minus(Duration.ofDays(1))), office);
        enquiries.closeEnquiry(closed.id(), new com.akshara.frontoffice.FrontOfficeForms.CloseForm(
                "Told her the school works 8:30 to 12:30 on Saturdays."), office);
        enquiries.logCall(new CallForm(CallDirection.INCOMING, "Vikram Singh", "9876513001",
                "Asked for his daughter's bonafide certificate.", "Told him it will be ready on Monday.",
                today.minusDays(1), officeId, now.minus(Duration.ofDays(2))), office);
        enquiries.logCall(new CallForm(CallDirection.OUTGOING, "Bharat Printers", "9876513002",
                "Ordered 500 letterheads.", "Delivery promised by Friday.", null, null,
                now.minus(Duration.ofHours(5))), office);
        enquiries.logCall(new CallForm(CallDirection.INCOMING, "Neha Kapoor", "9876513003",
                "Wants to meet the principal about her son's bus route.", null, today, principalId,
                now.minus(Duration.ofHours(1))), office);
        return null;
    }

    private Object courierEntries(Actor office, LocalDate today) {
        courier.add(new CourierForm(CourierDirection.INWARD, "CBSE circulars (envelope)", "CBSE Regional Office",
                "Principal", today, "India Post (Speed Post)", "EK123456789IN", "Suresh Rao", null), office);
        courier.add(new CourierForm(CourierDirection.OUTWARD, "Transfer certificate of Rohit Verma",
                "Akshara Demo School", "Mr. Anil Verma, Pune", today.minusDays(1), "DTDC", "D40098765",
                "Suresh Rao", "Sent by courier as the family moved to Pune."), office);
        return null;
    }

    private UUID pupil(String name) {
        List<StudentRow> found = students.list(new StudentQuery(null, null, null, StudentStatus.ACTIVE, name, 0, 5))
                .items();
        return found.stream().filter(r -> r.fullName().equals(name)).map(StudentRow::id).findFirst().orElse(null);
    }

    /** Runs one piece of demo data in its own transaction; a piece that cannot be seeded is logged and skipped. */
    private boolean step(UUID tenantId, String what, Supplier<Object> work) {
        try {
            TenantContext.runAs(tenantId, () -> tx.execute(status -> work.get()));
            return true;
        } catch (RuntimeException e) {
            log.warn("Skipped demo front office data for {}: {}", what, e.getMessage());
            return false;
        }
    }
}
