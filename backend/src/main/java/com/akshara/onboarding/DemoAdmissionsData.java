package com.akshara.onboarding;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.admissions.AdmissionForms.AdmitRequest;
import com.akshara.admissions.AdmissionForms.ApplicationRequest;
import com.akshara.admissions.AdmissionForms.FeeRequest;
import com.akshara.admissions.AdmissionForms.GuardianInput;
import com.akshara.admissions.AdmissionForms.PublicEnquiry;
import com.akshara.admissions.AdmissionForms.SlotRequest;
import com.akshara.admissions.AdmissionForms.StageRequest;
import com.akshara.admissions.AdmissionTypes.AssessmentKind;
import com.akshara.admissions.AdmissionTypes.AssessmentMode;
import com.akshara.admissions.AdmissionTypes.FeeStatus;
import com.akshara.admissions.AdmissionTypes.PaymentMethod;
import com.akshara.admissions.AdmissionTypes.YearChoice;
import com.akshara.admissions.AdmissionsService;
import com.akshara.admissions.ApplicationSource;
import com.akshara.admissions.ApplicationStage;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.TenantContext;
import com.akshara.students.Gender;
import com.akshara.students.GuardianRelation;

/**
 * The demo school's admissions pipeline for 2026-27: fifteen applications spread over every stage, with notes, fees,
 * three upcoming tests and interviews, two offers and one child already admitted as a student. Entered by the front
 * office (two arrived through the public enquiry form). Names are invented; phone numbers come from the unused
 * 98765 02xxx range. Runs in one transaction as the demo school, after the school's other demo data.
 */
final class DemoAdmissionsData {

    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Kolkata");

    /** The demo student created by admitting an offered application. */
    static final int ADMITTED_STUDENTS = 1;
    static final int APPLICATIONS = 15;
    static final int UPCOMING_SLOTS = 3;
    static final String ADMISSION_NO = "AKS/2026/101";

    private final AdmissionsService admissions;
    private final AcademicsService academics;
    private final TransactionTemplate tx;

    private Actor frontOffice;
    private UUID principalId;
    private Map<String, ClassView> classes;
    private YearInfo year;
    private LocalDate today;
    private int phone;

    DemoAdmissionsData(AdmissionsService admissions, AcademicsService academics, TransactionTemplate tx) {
        this.admissions = admissions;
        this.academics = academics;
        this.tx = tx;
    }

    /** Returns the number of applications created. */
    int seed(UUID tenantId, UUID frontOfficeId, UUID principalId) {
        return TenantContext.runAs(tenantId, () -> tx.execute(status -> seed(frontOfficeId, principalId)));
    }

    private int seed(UUID frontOfficeId, UUID principalId) {
        String frontOfficeName = DemoDataSeeder.PEOPLE.stream().filter(p -> p.role().equals("FRONT_OFFICE"))
                .findFirst().orElseThrow().name();
        this.frontOffice = new Actor(frontOfficeId, frontOfficeName);
        this.principalId = principalId;
        this.classes = academics.classes().stream().collect(Collectors.toMap(ClassView::name, c -> c));
        this.year = academics.years().stream().filter(YearInfo::current).findFirst().orElseThrow();
        this.today = LocalDate.now(SCHOOL_ZONE);
        this.phone = 2001;

        // Two families used the school's public enquiry form.
        website("Komal Bhatt", GuardianRelation.MOTHER, "Aanya", "Bhatt", Gender.FEMALE, "LKG",
                "We are moving to Hyderabad in December. Is there a mid-year admission for LKG?");
        website("Harish Rao", GuardianRelation.FATHER, "Vedant", "Rao", Gender.MALE, "UKG", null);

        // Enquiries taken by phone, at the desk and through a referral.
        create("Myra", "Saxena", Gender.FEMALE, "Class 1", ApplicationSource.PHONE, ApplicationStage.ENQUIRY,
                "Nidhi Saxena", GuardianRelation.MOTHER, 1, "Called about the fees and the school bus.");
        create("Reyan", "Thomas", Gender.MALE, "Class 3", ApplicationSource.WALK_IN, ApplicationStage.ENQUIRY,
                "Jacob Thomas", GuardianRelation.FATHER, 3, "Visited with the family; took a prospectus.");
        UUID referred = create("Nivaan", "Goyal", Gender.MALE, "Class 7", ApplicationSource.REFERRAL,
                ApplicationStage.ENQUIRY, "Pooja Goyal", GuardianRelation.MOTHER, 0,
                "Referred by a parent in Class 7.");
        admissions.addNote(referred, "Asked for a call back after 6 pm.", frontOffice);

        // Applications: forms handed in, one fee paid.
        UUID saisha = create("Saisha", "Menon", Gender.FEMALE, "LKG", ApplicationSource.REFERRAL,
                ApplicationStage.APPLICATION, "Divya Menon", GuardianRelation.MOTHER, 2, "Referred by the Pillai family.");
        paid(saisha, 50_000, PaymentMethod.UPI, "UPI-310245");
        create("Kian", "Malhotra", Gender.MALE, "Class 2", ApplicationSource.WALK_IN, ApplicationStage.APPLICATION,
                "Rahul Malhotra", GuardianRelation.FATHER, 2, "Birth certificate still to come.");

        // Tests and interviews coming up.
        UUID advika = create("Advika", "Reddy", Gender.FEMALE, "Class 1", ApplicationSource.WEBSITE,
                ApplicationStage.APPLICATION, "Swathi Reddy", GuardianRelation.MOTHER, 0, null);
        paid(advika, 50_000, PaymentMethod.CASH, null);
        slot(advika, AssessmentKind.INTERVIEW, 2, LocalTime.of(10, 0), AssessmentMode.IN_PERSON, "Principal's office",
                null, principalId);
        UUID ishan = create("Ishan", "Kumar", Gender.MALE, "Class 4", ApplicationSource.REFERRAL,
                ApplicationStage.APPLICATION, "Sunita Kumar", GuardianRelation.MOTHER, 0, null);
        admissions.recordFee(ishan, new FeeRequest(FeeStatus.WAIVED, null, null, null, today,
                "Child of a staff member: fee waived."), frontOffice);
        slot(ishan, AssessmentKind.TEST, 4, LocalTime.of(9, 30), AssessmentMode.IN_PERSON, "Room 12, main block",
                null, null);
        UUID zoya = create("Zoya", "Hussain", Gender.FEMALE, "Class 6", ApplicationSource.PHONE,
                ApplicationStage.APPLICATION, "Imtiaz Hussain", GuardianRelation.FATHER, 0,
                "Family is in Dubai until next month; interview online.");
        paid(zoya, 50_000, PaymentMethod.BANK_TRANSFER, "NEFT-88213");
        slot(zoya, AssessmentKind.INTERVIEW, 6, LocalTime.of(16, 0), AssessmentMode.ONLINE, null,
                "https://meet.example.com/akshara-zoya", principalId);

        // Offers waiting for the family's answer.
        UUID aditi = create("Aditi", "Joshi", Gender.FEMALE, "UKG", ApplicationSource.WALK_IN,
                ApplicationStage.APPLICATION, "Manasi Joshi", GuardianRelation.MOTHER, 0, null);
        paid(aditi, 50_000, PaymentMethod.UPI, "UPI-310877");
        offer(aditi, "Met the principal; reads simple words confidently.", null);
        UUID arnav = create("Arnav", "Pillai", Gender.MALE, "Class 1", ApplicationSource.REFERRAL,
                ApplicationStage.APPLICATION, "Deepak Pillai", GuardianRelation.FATHER, 0, null);
        paid(arnav, 50_000, PaymentMethod.CARD, "CARD-4471");
        offer(arnav, "Interview done; settled well with the class.", today.plusDays(10));

        // Admitted: now a student of LKG A.
        UUID riaan = create("Riaan", "Kapoor", Gender.MALE, "LKG", ApplicationSource.WALK_IN,
                ApplicationStage.APPLICATION, "Shalini Kapoor", GuardianRelation.MOTHER, 0, null);
        paid(riaan, 50_000, PaymentMethod.UPI, "UPI-309912");
        offer(riaan, "Offer accepted by the family.", null);
        UUID lkgA = classes.get("LKG").sections().stream().filter(s -> s.name().equals("A")).findFirst()
                .orElseThrow().id();
        admissions.admit(riaan, new AdmitRequest(lkgA, ADMISSION_NO, null, today, null), frontOffice);

        // Closed.
        UUID kabir = create("Kabir", "Sethi", Gender.MALE, "Class 5", ApplicationSource.PHONE,
                ApplicationStage.APPLICATION, "Mohit Sethi", GuardianRelation.FATHER, 0, null);
        admissions.moveStage(kabir, new StageRequest(ApplicationStage.REJECTED,
                "No seats left in Class 5 this year; added to next year's list.", null, null), frontOffice);
        UUID tara = create("Tara", "Fernandes", Gender.FEMALE, "Class 2", ApplicationSource.WEBSITE,
                ApplicationStage.ENQUIRY, "Lydia Fernandes", GuardianRelation.MOTHER, 0, null);
        admissions.moveStage(tara, new StageRequest(ApplicationStage.WITHDRAWN,
                "Family chose a school closer to home.", null, null), frontOffice);
        return APPLICATIONS;
    }

    private void website(String parent, GuardianRelation relation, String firstName, String lastName, Gender gender,
            String className, String message) {
        admissions.receiveEnquiry(new PublicEnquiry(parent, relation, nextPhone(), null, firstName, lastName,
                dateOfBirth(className, gender), className, YearChoice.CURRENT, message, true,
                AdmissionsService.CONSENT_VERSION, null));
    }

    private UUID create(String firstName, String lastName, Gender gender, String className, ApplicationSource source,
            ApplicationStage stage, String guardian, GuardianRelation relation, int followUpInDays, String note) {
        LocalDate followUp = followUpInDays > 0 ? today.plusDays(followUpInDays) : null;
        UUID counsellor = source == ApplicationSource.REFERRAL ? frontOffice.id() : null;
        ApplicationRequest form = new ApplicationRequest(stage, firstName, lastName, dateOfBirth(className, gender),
                gender, null, classes.get(className).id(), year.id(), source, counsellor, followUp, note,
                List.of(new GuardianInput(guardian, relation, nextPhone(), null, true)));
        return admissions.create(form, frontOffice).id();
    }

    private void paid(UUID id, long amountPaise, PaymentMethod method, String reference) {
        admissions.recordFee(id, new FeeRequest(FeeStatus.PAID, amountPaise, method, reference, today, null),
                frontOffice);
    }

    private void slot(UUID id, AssessmentKind kind, int inDays, LocalTime at, AssessmentMode mode, String location,
            String link, UUID interviewer) {
        Instant when = today.plusDays(inDays).atTime(at).atZone(SCHOOL_ZONE).toInstant();
        admissions.scheduleSlot(id, new SlotRequest(kind, when, mode, location, link, interviewer), frontOffice);
    }

    private void offer(UUID id, String note, LocalDate validUntil) {
        admissions.addNote(id, note, frontOffice);
        admissions.moveStage(id, new StageRequest(ApplicationStage.OFFERED, null, today, validUntil), frontOffice);
    }

    /** About the right age for the class: LKG children are born four years before the year starts. */
    private LocalDate dateOfBirth(String className, Gender gender) {
        int level = DemoSchoolData.level(className);
        return LocalDate.of(year.startsOn().getYear() - 5 - level, 1 + (phone * 5) % 12,
                gender == Gender.FEMALE ? 4 + phone % 20 : 2 + phone % 25);
    }

    private String nextPhone() {
        return "98765" + String.format("%05d", phone++);
    }
}
