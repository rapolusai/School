package com.akshara.admissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** The admissions pipeline as staff use it: stages, timeline, tests and interviews, fees, offers and admitting. */
@RecordApplicationEvents
class AdmissionsIT extends IntegrationTest {

    private static final String BASE = "/api/admissions/applications";

    @Autowired
    private ApplicationEvents events;

    private School school;
    private Session admin;
    private SchoolFixtures fixtures;
    private String yearId;
    private String classOne;
    private String classTwo;
    private String sectionA;

    @BeforeEach
    void schoolWithClasses() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        yearId = fixtures.currentYear(admin);
        classOne = fixtures.schoolClass(admin, "Class 1");
        classTwo = fixtures.schoolClass(admin, "Class 2");
        sectionA = fixtures.section(admin, classOne, "A", 30);
    }

    @Test
    void anApplicationMovesThroughThePipelineWithATimeline() throws Exception {
        String id = TestApi.read(api.post(BASE, admin.accessToken(), """
                {"firstName":"Aarav","lastName":"Mehta","dateOfBirth":"2020-02-11","gender":"MALE",
                 "previousSchool":"Little Steps Playschool","classId":"%s","academicYearId":"%s","source":"WALK_IN",
                 "followUpOn":"%s","note":"Came in with his mother.",
                 "guardians":[{"name":"Priya Mehta","relation":"MOTHER","phone":"+91 98765 43210",
                               "email":"priya@family.test"},
                              {"name":"Rohan Mehta","relation":"FATHER","phone":"98765-43211"}]}
                """.formatted(classOne, yearId, today().plusDays(2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stage").value("ENQUIRY"))
                .andExpect(jsonPath("$.childName").value("Aarav Mehta"))
                .andExpect(jsonPath("$.className").value("Class 1"))
                .andExpect(jsonPath("$.academicYearName").value("2026-27"))
                .andExpect(jsonPath("$.daysInStage").value(0))
                .andExpect(jsonPath("$.nextStages", contains("APPLICATION", "REJECTED", "WITHDRAWN")))
                // Numbers are stored as 10 digits; the first contact is primary when none is marked.
                .andExpect(jsonPath("$.guardians[0].phone").value("9876543210"))
                .andExpect(jsonPath("$.guardians[0].primary").value(true))
                .andExpect(jsonPath("$.guardians[1].phone").value("9876543211"))
                .andExpect(jsonPath("$.guardians[1].primary").value(false))
                .andExpect(jsonPath("$.timeline.length()").value(1))
                .andExpect(jsonPath("$.timeline[0].kind").value("CREATED"))
                .andExpect(jsonPath("$.timeline[0].actorName").value("Asha Admin"))
                .andExpect(jsonPath("$.timeline[0].note").value("Came in with his mother."))
                .andExpect(jsonPath("$.timeline[0].details.source").value("WALK_IN")), "$.id");

        // The list masks the contact's number.
        api.get("/api/admissions/applications", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].contactName").value("Priya Mehta"))
                .andExpect(jsonPath("$.items[0].contactPhone").value("98•••••210"))
                .andExpect(jsonPath("$.stageCounts.ENQUIRY").value(1));

        // Refused moves answer 409 and name the stage.
        move(id, "OFFERED").andExpect(status().isConflict()).andExpect(jsonPath("$.errors.stage").exists());
        move(id, "ENQUIRY").andExpect(status().isConflict());
        move(id, "ADMITTED").andExpect(status().isConflict());
        api.post(BASE + "/" + id + "/stage", admin.accessToken(), "{}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.stage").exists());

        api.post(BASE + "/" + id + "/stage", admin.accessToken(), """
                {"stage":"APPLICATION","note":"Form handed in."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("APPLICATION"))
                .andExpect(jsonPath("$.timeline[0].kind").value("STAGE_CHANGED"))
                .andExpect(jsonPath("$.timeline[0].fromStage").value("ENQUIRY"))
                .andExpect(jsonPath("$.timeline[0].toStage").value("APPLICATION"))
                .andExpect(jsonPath("$.timeline[0].note").value("Form handed in."));
        move(id, "ENQUIRY").andExpect(status().isConflict());

        // Notes and the application fee.
        api.post(BASE + "/" + id + "/notes", admin.accessToken(), """
                {"note":"Asked about the school bus."}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timeline[0].kind").value("NOTE"))
                .andExpect(jsonPath("$.timeline[0].note").value("Asked about the school bus."));
        api.post(BASE + "/" + id + "/notes", admin.accessToken(), """
                {"note":"  "}""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.note").exists());
        api.put(BASE + "/" + id + "/fee", admin.accessToken(), """
                {"status":"PAID","amountPaise":50000,"paidOn":"%s"}""".formatted(today()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.method").exists());
        api.put(BASE + "/" + id + "/fee", admin.accessToken(), """
                {"status":"PAID","amountPaise":50000,"method":"UPI","paidOn":"%s"}""".formatted(today().plusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.paidOn").exists());
        api.put(BASE + "/" + id + "/fee", admin.accessToken(), """
                {"status":"PAID","amountPaise":0,"method":"UPI","paidOn":"%s"}""".formatted(today()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amountPaise").exists());
        api.put(BASE + "/" + id + "/fee", admin.accessToken(), """
                {"status":"PAID","amountPaise":50000,"method":"UPI","reference":"UPI-7781","paidOn":"%s"}"""
                .formatted(today()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee.status").value("PAID"))
                .andExpect(jsonPath("$.fee.amountPaise").value(50000))
                .andExpect(jsonPath("$.fee.method").value("UPI"))
                .andExpect(jsonPath("$.fee.reference").value("UPI-7781"))
                .andExpect(jsonPath("$.timeline[0].kind").value("FEE_RECORDED"))
                .andExpect(jsonPath("$.timeline[0].details.amountPaise").value(50000));

        // Scheduling the first test moves the application to the assessment stage.
        Instant at = Instant.now().plus(Duration.ofDays(3)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        String slotId = TestApi.read(api.post(BASE + "/" + id + "/slots", admin.accessToken(), """
                {"kind":"TEST","scheduledAt":"%s","mode":"IN_PERSON","location":"Room 12"}""".formatted(at))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stage").value("ASSESSMENT"))
                .andExpect(jsonPath("$.slots[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.slots[0].location").value("Room 12"))
                .andExpect(jsonPath("$.timeline[*].kind", hasItems("SLOT_SCHEDULED", "STAGE_CHANGED"))), "$.slots[0].id");
        api.get("/api/admissions/slots/upcoming", admin.accessToken())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].childName").value("Aarav Mehta"))
                .andExpect(jsonPath("$[0].className").value("Class 1"));
        api.get("/api/admissions/applications", admin.accessToken())
                .andExpect(jsonPath("$.items[0].nextSlotAt").value(at.toString()));

        // Bad slots.
        api.post(BASE + "/" + id + "/slots", admin.accessToken(), """
                {"kind":"INTERVIEW","scheduledAt":"%s","mode":"IN_PERSON","location":"Office"}"""
                .formatted(Instant.now().minus(Duration.ofHours(1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.scheduledAt").exists());
        api.post(BASE + "/" + id + "/slots", admin.accessToken(), """
                {"kind":"INTERVIEW","scheduledAt":"%s","mode":"ONLINE","meetingLink":"meet.example.com/abc"}"""
                .formatted(at)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.meetingLink").exists());
        api.post(BASE + "/" + id + "/slots", admin.accessToken(), """
                {"kind":"INTERVIEW","scheduledAt":"%s","mode":"IN_PERSON"}"""
                .formatted(at)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.location").exists());

        // Rescheduling, then the outcome.
        Instant later = at.plus(Duration.ofDays(1));
        api.put(BASE + "/" + id + "/slots/" + slotId, admin.accessToken(), """
                {"kind":"INTERVIEW","scheduledAt":"%s","mode":"ONLINE","meetingLink":"https://meet.example.com/abc",
                 "location":"ignored for online"}""".formatted(later))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[0].kind").value("INTERVIEW"))
                .andExpect(jsonPath("$.slots[0].mode").value("ONLINE"))
                .andExpect(jsonPath("$.slots[0].location").doesNotExist())
                .andExpect(jsonPath("$.slots[0].scheduledAt").value(later.toString()))
                .andExpect(jsonPath("$.timeline[0].kind").value("SLOT_RESCHEDULED"))
                .andExpect(jsonPath("$.timeline[0].details.previous").value(at.toString()));
        api.post(BASE + "/" + id + "/slots/" + slotId + "/outcome", admin.accessToken(), """
                {"notes":"Reads fluently; confident."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[0].status").value("DONE"))
                .andExpect(jsonPath("$.slots[0].outcomeNotes").value("Reads fluently; confident."))
                .andExpect(jsonPath("$.timeline[0].kind").value("SLOT_OUTCOME"));
        api.put(BASE + "/" + id + "/slots/" + slotId, admin.accessToken(), """
                {"kind":"INTERVIEW","scheduledAt":"%s","mode":"IN_PERSON","location":"Office"}""".formatted(later))
                .andExpect(status().isConflict());
        api.post(BASE + "/" + id + "/slots/" + slotId + "/cancel", admin.accessToken(), null)
                .andExpect(status().isConflict());
        api.post(BASE + "/" + id + "/slots/" + UUID.randomUUID() + "/cancel", admin.accessToken(), null)
                .andExpect(status().isNotFound());

        // A second slot can be cancelled.
        String second = TestApi.read(api.post(BASE + "/" + id + "/slots", admin.accessToken(), """
                {"kind":"INTERVIEW","scheduledAt":"%s","mode":"IN_PERSON","location":"Principal's office"}"""
                .formatted(later.plus(Duration.ofDays(1)))).andExpect(status().isCreated()), "$.slots[0].id");
        api.post(BASE + "/" + id + "/slots/" + second + "/cancel", admin.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.timeline[0].kind").value("SLOT_CANCELLED"));
        api.get("/api/admissions/slots/upcoming", admin.accessToken()).andExpect(jsonPath("$.length()").value(0));

        // The offer: default dates are today and two weeks on.
        move(id, "OFFERED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("OFFERED"))
                .andExpect(jsonPath("$.offer.offeredOn").value(today().toString()))
                .andExpect(jsonPath("$.offer.validUntil").value(today().plusDays(14).toString()))
                .andExpect(jsonPath("$.nextStages", contains("ADMITTED", "REJECTED", "WITHDRAWN")));
        api.put(BASE + "/" + id + "/offer", admin.accessToken(), """
                {"offeredOn":"%s","validUntil":"%s"}""".formatted(today(), today().minusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.validUntil").exists());
        api.put(BASE + "/" + id + "/offer", admin.accessToken(), """
                {"offeredOn":"%s","validUntil":"%s"}""".formatted(today(), today().plusDays(30)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offer.validUntil").value(today().plusDays(30).toString()))
                .andExpect(jsonPath("$.timeline[0].kind").value("OFFER_UPDATED"));
        // Admitting goes through "Admit as student", never a plain stage move.
        move(id, "ADMITTED").andExpect(status().isConflict()).andExpect(jsonPath("$.errors.stage").exists());

        api.get("/api/admissions/board", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lanes[*].stage",
                        contains("ENQUIRY", "APPLICATION", "ASSESSMENT", "OFFERED", "ADMITTED")))
                .andExpect(jsonPath("$.lanes[3].total").value(1))
                .andExpect(jsonPath("$.lanes[3].cards[0].childName").value("Aarav Mehta"))
                .andExpect(jsonPath("$.lanes[0].cards.length()").value(0))
                .andExpect(jsonPath("$.closed").value(0));
        api.get("/api/admissions/summary", admin.accessToken())
                .andExpect(jsonPath("$.offersPending").value(1))
                .andExpect(jsonPath("$.openEnquiries").value(0))
                .andExpect(jsonPath("$.admittedThisYear").value(0));

        api.get(BASE + "/" + id, admin.accessToken())
                .andExpect(jsonPath("$.timeline[*].kind", hasItems("CREATED", "STAGE_CHANGED", "NOTE", "FEE_RECORDED",
                        "SLOT_SCHEDULED", "SLOT_RESCHEDULED", "SLOT_OUTCOME", "SLOT_CANCELLED", "OFFER_UPDATED")))
                .andExpect(jsonPath("$.timeline[-1].kind").value("CREATED"));

        assertThat(events.stream(ApplicationStageChanged.class).filter(e -> e.applicationId().toString().equals(id))
                .map(e -> e.from() + ">" + e.to()))
                .containsExactly("ENQUIRY>APPLICATION", "APPLICATION>ASSESSMENT", "ASSESSMENT>OFFERED");
        api.get("/api/audit-events?limit=100", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("application.created", "application.stage_changed",
                        "application.note_added", "application.fee_recorded", "application.offer_updated",
                        "assessment_slot.scheduled", "assessment_slot.rescheduled",
                        "assessment_slot.outcome_recorded", "assessment_slot.cancelled")));
    }

    @Test
    void admittingCreatesOneStudentWithTheGuardiansInTheSection() throws Exception {
        String id = offered("Ishita", "Verma", classOne, "Kavita Verma", "9811122233");
        String otherSection = fixtures.section(admin, classTwo, "A", 30);

        String admitJson = """
                {"sectionId":"%s","admissionNo":"AKS/2026/101","rollNo":12,"gender":"FEMALE"}""";
        api.post(BASE + "/" + id + "/admit", admin.accessToken(), admitJson.formatted(otherSection))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.sectionId").exists());
        api.post(BASE + "/" + id + "/admit", admin.accessToken(), """
                {"sectionId":"%s","admissionNo":"AKS/2026/101"}""".formatted(sectionA))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.gender").exists());
        api.post(BASE + "/" + id + "/admit", admin.accessToken(), """
                {"sectionId":"%s","admissionNo":"bad number!","gender":"FEMALE"}""".formatted(sectionA))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.admissionNo").exists());

        String studentId = TestApi.read(api.post(BASE + "/" + id + "/admit", admin.accessToken(),
                admitJson.formatted(sectionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("ADMITTED"))
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.nextStages.length()").value(0))
                .andExpect(jsonPath("$.timeline[0].kind").value("STAGE_CHANGED"))
                .andExpect(jsonPath("$.timeline[0].toStage").value("ADMITTED"))
                .andExpect(jsonPath("$.timeline[0].details.admissionNo").value("AKS/2026/101")), "$.studentId");

        api.get("/api/students/" + studentId, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ishita Verma"))
                .andExpect(jsonPath("$.admissionNo").value("AKS/2026/101"))
                .andExpect(jsonPath("$.dateOfBirth").value("2020-01-15"))
                .andExpect(jsonPath("$.currentEnrollment.sectionId").value(sectionA))
                .andExpect(jsonPath("$.currentEnrollment.rollNo").value(12))
                .andExpect(jsonPath("$.currentEnrollment.academicYearId").value(yearId))
                .andExpect(jsonPath("$.guardians.length()").value(2))
                .andExpect(jsonPath("$.guardians[0].name").value("Kavita Verma"))
                .andExpect(jsonPath("$.guardians[0].phone").value("9811122233"))
                .andExpect(jsonPath("$.guardians[0].primary").value(true));

        // A second call (a double click, a retry) returns the same student and creates nothing.
        api.post(BASE + "/" + id + "/admit", admin.accessToken(), """
                {"sectionId":"%s","admissionNo":"AKS/2026/102","gender":"FEMALE"}""".formatted(sectionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentId").value(studentId));
        api.get("/api/students", admin.accessToken()).andExpect(jsonPath("$.total").value(1));
        assertThat(countRows("students.student", school.tenantId())).isEqualTo(1);

        // An admitted application is closed.
        move(id, "WITHDRAWN").andExpect(status().isConflict());
        api.put(BASE + "/" + id + "/fee", admin.accessToken(), """
                {"status":"WAIVED","paidOn":"%s"}""".formatted(today())).andExpect(status().isConflict());
        api.get("/api/admissions/summary", admin.accessToken())
                .andExpect(jsonPath("$.admittedThisYear").value(1))
                .andExpect(jsonPath("$.offersPending").value(0));
        api.get("/api/admissions/board", admin.accessToken())
                .andExpect(jsonPath("$.lanes[4].cards[0].id").value(id));

        assertThat(events.stream(ApplicationStageChanged.class)
                .filter(e -> e.applicationId().toString().equals(id) && e.to() == ApplicationStage.ADMITTED))
                .singleElement()
                .satisfies(e -> assertThat(e.studentId()).hasToString(studentId));
        api.get("/api/audit-events?limit=100", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("application.admitted", "student.created")));
    }

    @Test
    void onlyOfferedApplicationsCanBeAdmittedAndAdmissionNumbersStayUnique() throws Exception {
        String enquiry = create("Neel", classOne, "ENQUIRY", "Sunil Das", "9822233344");
        api.post(BASE + "/" + enquiry + "/admit", admin.accessToken(), """
                {"sectionId":"%s","admissionNo":"AKS-9","gender":"MALE"}""".formatted(sectionA))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.stage").exists());

        fixtures.student(admin, sectionA, "AKS-10", "Existing", null, "Lata Rao", "9876501001");
        String offered = offered("Meera", null, classOne, "Anil Iyer", "9833344455");
        api.post(BASE + "/" + offered + "/admit", admin.accessToken(), """
                {"sectionId":"%s","admissionNo":"AKS-10","gender":"FEMALE"}""".formatted(sectionA))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.admissionNo").exists());
        api.get(BASE + "/" + offered, admin.accessToken()).andExpect(jsonPath("$.stage").value("OFFERED"))
                .andExpect(jsonPath("$.studentId").doesNotExist());
    }

    @Test
    void closedApplicationsKeepTheirHistory() throws Exception {
        String id = create("Kabir", classOne, "APPLICATION", "Imran Khan", "9844455566");
        move(id, "REJECTED").andExpect(status().isOk()).andExpect(jsonPath("$.nextStages.length()").value(0));
        move(id, "APPLICATION").andExpect(status().isConflict());
        api.put(BASE + "/" + id, admin.accessToken(), applicationJson("Kabir", classOne, null, "Imran Khan",
                "9844455566")).andExpect(status().isConflict());
        api.post(BASE + "/" + id + "/slots", admin.accessToken(), """
                {"kind":"TEST","scheduledAt":"%s","mode":"IN_PERSON","location":"Hall"}"""
                .formatted(Instant.now().plus(Duration.ofDays(2)))).andExpect(status().isConflict());
        // Notes can still be added.
        api.post(BASE + "/" + id + "/notes", admin.accessToken(), """
                {"note":"Family moved to another city."}""").andExpect(status().isCreated());

        String withdrawn = create("Tara", classOne, "ENQUIRY", "Nisha Pillai", "9855566677");
        move(withdrawn, "WITHDRAWN").andExpect(status().isOk());
        api.get("/api/admissions/board", admin.accessToken()).andExpect(jsonPath("$.closed").value(2));
        api.get("/api/admissions/applications?stage=REJECTED", admin.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.stageCounts.WITHDRAWN").value(1));
    }

    @Test
    void editingAnApplicationRecordsWhatChanged() throws Exception {
        String counsellorEmail = "fo@" + school.code() + ".akshara.test";
        String counsellor = api.createUser(admin, "Farah Office", counsellorEmail, List.of("FRONT_OFFICE"));
        String id = create("Vihaan", classOne, "ENQUIRY", "Deepa Nair", "9866677788");
        api.get("/api/admissions/staff", admin.accessToken())
                .andExpect(jsonPath("$[*].name", hasItems("Asha Admin", "Farah Office")));

        api.put(BASE + "/" + id, admin.accessToken(), """
                {"firstName":"Vihaan","lastName":"Nair","dateOfBirth":"2019-08-01","classId":"%s",
                 "academicYearId":"%s","source":"PHONE","assignedToId":"%s","followUpOn":"%s",
                 "guardians":[{"name":"Deepa Nair","relation":"MOTHER","phone":"9866677788"},
                              {"name":"Ajay Nair","relation":"FATHER","phone":"9866677789","primary":true}]}"""
                .formatted(classTwo, yearId, counsellor, today().plusDays(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.className").value("Class 2"))
                .andExpect(jsonPath("$.assignedTo.name").value("Farah Office"))
                .andExpect(jsonPath("$.guardians[1].primary").value(true))
                .andExpect(jsonPath("$.timeline[0].kind").value("UPDATED"))
                .andExpect(jsonPath("$.timeline[0].details.changed",
                        containsInAnyOrder("lastName", "dateOfBirth", "class", "source", "counsellor", "followUpOn",
                                "guardians")));
        api.get("/api/admissions/applications?q=ajay", admin.accessToken()).andExpect(jsonPath("$.total").value(1));
        api.get("/api/admissions/applications?q=98666", admin.accessToken()).andExpect(jsonPath("$.total").value(1));
        api.get("/api/admissions/applications?q=nair&classId=" + classOne, admin.accessToken())
                .andExpect(jsonPath("$.total").value(0));
        api.get("/api/admissions/applications?source=PHONE", admin.accessToken())
                .andExpect(jsonPath("$.items[0].assignedToName").value("Farah Office"));
        api.get("/api/admissions/applications?q=100%25", admin.accessToken()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void setupThatApplicationsUseCannotBeDeleted() throws Exception {
        String nextYear = fixtures.year(admin, "2027-28", "2027-04-01", "2028-03-31", false);
        String nursery = fixtures.schoolClass(admin, "Nursery");
        api.post(BASE, admin.accessToken(), applicationJson("Asha", nursery, null, "Lata", "9876500001")
                .replace(yearId, nextYear)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.academicYearName").value("2027-28"));
        api.delete("/api/academics/years/" + nextYear, admin.accessToken()).andExpect(status().isConflict());
        api.delete("/api/academics/classes/" + nursery, admin.accessToken()).andExpect(status().isConflict());
        api.get(BASE, admin.accessToken()).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void formsAreCheckedFieldByField() throws Exception {
        api.post(BASE, admin.accessToken(), """
                {"firstName":"","dateOfBirth":"2099-01-01","classId":null,"academicYearId":null,"source":null,
                 "guardians":[]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.firstName").exists())
                .andExpect(jsonPath("$.errors.dateOfBirth").exists())
                .andExpect(jsonPath("$.errors.classId").value("Pick a class."))
                .andExpect(jsonPath("$.errors.academicYearId").value("Pick an academic year."))
                .andExpect(jsonPath("$.errors.source").exists())
                .andExpect(jsonPath("$.errors.guardians").value("Add at least one parent or guardian."));
        api.post(BASE, admin.accessToken(), applicationJson("Asha", classOne, null, "Lata", "12345"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$['errors']['guardians[0].phone']").exists());
        api.post(BASE, admin.accessToken(), applicationJson("Asha", classOne, "OFFERED", "Lata", "9876500001"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.stage").exists());
        api.post(BASE, admin.accessToken(), applicationJson("Asha", UUID.randomUUID().toString(), null, "Lata",
                "9876500001")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.classId").exists());

        String parentEmail = "parent@" + school.code() + ".akshara.test";
        String parent = api.createUser(admin, "Pari Parent", parentEmail, List.of("PARENT"));
        api.post(BASE, admin.accessToken(), """
                {"firstName":"Asha","dateOfBirth":"2019-05-01","classId":"%s","academicYearId":"%s",
                 "source":"WALK_IN","assignedToId":"%s",
                 "guardians":[{"name":"Lata","relation":"MOTHER","phone":"9876500001"}]}"""
                .formatted(classOne, yearId, parent))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.assignedToId").exists());
        api.post(BASE, admin.accessToken(), """
                {"firstName":"Asha","dateOfBirth":"2019-05-01","classId":"%s","academicYearId":"%s",
                 "source":"WALK_IN","followUpOn":"%s",
                 "guardians":[{"name":"Lata","relation":"MOTHER","phone":"9876500001"}]}"""
                .formatted(classOne, yearId, today().minusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.followUpOn").exists());
        String pastYear = fixtures.year(admin, "2024-25", "2024-06-01", "2025-03-31", false);
        api.post(BASE, admin.accessToken(), """
                {"firstName":"Asha","dateOfBirth":"2019-05-01","classId":"%s","academicYearId":"%s",
                 "source":"WALK_IN","guardians":[{"name":"Lata","relation":"MOTHER","phone":"9876500001"}]}"""
                .formatted(classOne, pastYear))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.academicYearId").exists());
        api.get(BASE, admin.accessToken()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void onlyAdmissionsStaffCanSeeOrChangeApplications() throws Exception {
        String id = create("Riya", classOne, "ENQUIRY", "Sita Ram", "9877788899");
        Session teacher = member("TEACHER");
        Session accountant = member("ACCOUNTANT");
        Session parent = member("PARENT");
        for (Session outsider : List.of(teacher, accountant, parent)) {
            for (String path : List.of("/api/admissions/summary", "/api/admissions/board", BASE, BASE + "/" + id,
                    "/api/admissions/slots/upcoming", "/api/admissions/staff")) {
                api.get(path, outsider.accessToken()).andExpect(status().isForbidden());
            }
            api.post(BASE, outsider.accessToken(), applicationJson("X", classOne, null, "Y", "9876500001"))
                    .andExpect(status().isForbidden());
            move(id, "APPLICATION", outsider).andExpect(status().isForbidden());
            api.post(BASE + "/" + id + "/notes", outsider.accessToken(), "{\"note\":\"x\"}")
                    .andExpect(status().isForbidden());
            api.post(BASE + "/" + id + "/admit", outsider.accessToken(), """
                    {"sectionId":"%s","admissionNo":"A-1","gender":"FEMALE"}""".formatted(sectionA))
                    .andExpect(status().isForbidden());
        }
        api.get("/api/admissions/board", null).andExpect(status().isUnauthorized());

        Session frontOffice = member("FRONT_OFFICE");
        api.get("/api/me", frontOffice.accessToken())
                .andExpect(jsonPath("$.permissions", hasItems("admissions.read", "admissions.manage")));
        api.post(BASE, frontOffice.accessToken(), applicationJson("Om", classOne, null, "Gita", "9876500002"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timeline[0].actorName").value("Front Office Member"));
        move(id, "APPLICATION", frontOffice).andExpect(status().isOk());

        Session principal = member("PRINCIPAL");
        api.get(BASE, principal.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        move(id, "WITHDRAWN", principal).andExpect(status().isOk());

        api.get("/api/me", teacher.accessToken())
                .andExpect(jsonPath("$.permissions", not(hasItem("admissions.read"))));
    }

    @Test
    void anotherSchoolNeverSeesOrTouchesTheseApplications() throws Exception {
        String id = offered("Zoya", "Ali", classOne, "Farhan Ali", "9888899900");
        String slotOwner = create("Dev", classOne, "APPLICATION", "Ritu Sen", "9899900011");
        String slotId = TestApi.read(api.post(BASE + "/" + slotOwner + "/slots", admin.accessToken(), """
                {"kind":"TEST","scheduledAt":"%s","mode":"IN_PERSON","location":"Hall"}"""
                .formatted(Instant.now().plus(Duration.ofDays(2)))), "$.slots[0].id");

        School otherSchool = api.signup();
        Session other = api.login(otherSchool);
        String otherYear = fixtures.currentYear(other);
        String otherClass = fixtures.schoolClass(other, "Class 1");
        String otherSection = fixtures.section(other, otherClass, "A", 30);

        api.get(BASE + "/" + id, other.accessToken()).andExpect(status().isNotFound());
        move(id, "REJECTED", other).andExpect(status().isNotFound());
        api.put(BASE + "/" + id, other.accessToken(), applicationJson("Zoya", otherClass, null, "Farhan Ali",
                "9888899900").replace(yearId, otherYear)).andExpect(status().isNotFound());
        api.post(BASE + "/" + id + "/notes", other.accessToken(), "{\"note\":\"x\"}")
                .andExpect(status().isNotFound());
        api.put(BASE + "/" + id + "/fee", other.accessToken(), """
                {"status":"WAIVED","paidOn":"%s"}""".formatted(today())).andExpect(status().isNotFound());
        api.put(BASE + "/" + id + "/offer", other.accessToken(), """
                {"offeredOn":"%s"}""".formatted(today())).andExpect(status().isNotFound());
        api.post(BASE + "/" + id + "/admit", other.accessToken(), """
                {"sectionId":"%s","admissionNo":"X-1","gender":"FEMALE"}""".formatted(otherSection))
                .andExpect(status().isNotFound());
        api.post(BASE + "/" + slotOwner + "/slots/" + slotId + "/cancel", other.accessToken(), null)
                .andExpect(status().isNotFound());
        api.post(BASE + "/" + slotOwner + "/slots", other.accessToken(), """
                {"kind":"TEST","scheduledAt":"%s","mode":"IN_PERSON","location":"Hall"}"""
                .formatted(Instant.now().plus(Duration.ofDays(2)))).andExpect(status().isNotFound());

        api.get(BASE, other.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/admissions/board", other.accessToken())
                .andExpect(jsonPath("$.lanes[3].total").value(0))
                .andExpect(jsonPath("$.lanes[3].cards.length()").value(0));
        api.get("/api/admissions/slots/upcoming", other.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.get("/api/admissions/summary", other.accessToken())
                .andExpect(jsonPath("$.offersPending").value(0))
                .andExpect(jsonPath("$.inProgress").value(0));

        // Ids from the first school in a body are refused field by field.
        api.post(BASE, other.accessToken(), applicationJson("Ali", classOne, null, "X", "9876500001")
                .replace(yearId, otherYear))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.classId").exists());
        api.post(BASE, other.accessToken(), applicationJson("Ali", otherClass, null, "X", "9876500001"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.academicYearId").exists());
        String own = TestApi.read(api.post(BASE, other.accessToken(), applicationJson("Ali", otherClass,
                "APPLICATION", "X", "9876500001").replace(yearId, otherYear)).andExpect(status().isCreated()), "$.id");
        move(own, "OFFERED", other).andExpect(status().isOk());
        api.post(BASE + "/" + own + "/admit", other.accessToken(), """
                {"sectionId":"%s","admissionNo":"X-1","gender":"FEMALE"}""".formatted(sectionA))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.sectionId").exists());
        String adminId = TestApi.read(api.get("/api/me", admin.accessToken()), "$.id");
        api.put(BASE + "/" + own, other.accessToken(), applicationJson("Ali", otherClass, null, "X", "9876500001")
                .replace(yearId, otherYear).replace("\"source\":\"WALK_IN\"",
                        "\"source\":\"WALK_IN\",\"assignedToId\":\"" + adminId + "\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.assignedToId").exists());

        // The first school's data is untouched.
        api.get(BASE + "/" + id, admin.accessToken())
                .andExpect(jsonPath("$.stage").value("OFFERED"))
                .andExpect(jsonPath("$.timeline[0].kind").value("STAGE_CHANGED"));
    }

    // ------------------------------------------------------------------ helpers

    private Session member(String role) throws Exception {
        String name = switch (role) {
            case "FRONT_OFFICE" -> "Front Office Member";
            default -> role.charAt(0) + role.substring(1).toLowerCase() + " Member";
        };
        String email = role.toLowerCase() + "@" + school.code() + ".akshara.test";
        api.createUser(admin, name, email, List.of(role));
        return api.login(school.code(), email, TestApi.PASSWORD);
    }

    private org.springframework.test.web.servlet.ResultActions move(String id, String stage) throws Exception {
        return move(id, stage, admin);
    }

    private org.springframework.test.web.servlet.ResultActions move(String id, String stage, Session who)
            throws Exception {
        return api.post(BASE + "/" + id + "/stage", who.accessToken(), """
                {"stage":"%s"}""".formatted(stage));
    }

    private String applicationJson(String firstName, String classId, String stage, String guardian, String phone) {
        return """
                {"stage":%s,"firstName":"%s","dateOfBirth":"2020-01-15","classId":"%s","academicYearId":"%s",
                 "source":"WALK_IN","guardians":[{"name":"%s","relation":"MOTHER","phone":"%s"}]}"""
                .formatted(stage == null ? "null" : "\"" + stage + "\"", firstName, classId, yearId, guardian, phone);
    }

    private String create(String firstName, String classId, String stage, String guardian, String phone)
            throws Exception {
        return TestApi.read(api.post(BASE, admin.accessToken(), applicationJson(firstName, classId, stage, guardian,
                phone)).andExpect(status().isCreated()), "$.id");
    }

    /** An application at the OFFERED stage, with two guardians. */
    private String offered(String firstName, String lastName, String classId, String guardian, String phone)
            throws Exception {
        String id = TestApi.read(api.post(BASE, admin.accessToken(), """
                {"stage":"APPLICATION","firstName":"%s","lastName":%s,"dateOfBirth":"2020-01-15","classId":"%s",
                 "academicYearId":"%s","source":"REFERRAL",
                 "guardians":[{"name":"%s","relation":"MOTHER","phone":"%s","primary":true},
                              {"name":"Second Guardian","relation":"FATHER","phone":"9700000001"}]}"""
                .formatted(firstName, lastName == null ? "null" : "\"" + lastName + "\"", classId, yearId, guardian,
                        phone)).andExpect(status().isCreated()), "$.id");
        move(id, "OFFERED").andExpect(status().isOk());
        return id;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    private static long countRows(String table, UUID tenantId) throws Exception {
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement("select count(*) from " + table + " where tenant_id = ?")) {
            s.setObject(1, tenantId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
