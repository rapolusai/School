package com.akshara.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.akshara.support.FeeFixtures;
import com.akshara.support.FeeFixtures.Heads;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * Erasure for a child who has left: guarded by the request, the student's status and a second confirmation; keeps
 * fee receipts; never reaches another school.
 */
@RecordApplicationEvents
class ErasureIT extends PrivacyTestBase {

    @Autowired
    private ApplicationEvents events;

    @Test
    void onlyAChildWhoHasLeftIsErasedAndReceiptsAreKept() throws Exception {
        FeeFixtures fees = new FeeFixtures(api, mvc);
        Heads heads = fees.defaultHeads(admin);
        fees.publishedStructure(admin, yearId, classId, heads);
        String receipt = fees.cash(admin, kabir, 11_500_00);
        acceptForBoth(publish(null));
        api.post("/api/privacy/students/" + kabir + "/consents", admin.accessToken(), """
                {"givenByName":"Farah Khan","signedOn":"%s","photos":true,"whatsapp":true}""".formatted(today()))
                .andExpect(status().isCreated());

        String request = submit(otherParent, "ERASURE", "CHILD", kabir, "Kabir has moved to another city.");
        String access = submit(otherParent, "ACCESS", "CHILD", kabir, null);
        api.post("/api/privacy/requests/" + access + "/export", admin.accessToken(), null)
                .andExpect(status().isOk());

        // The admission number must be typed again, and the child must have left the school.
        api.post("/api/privacy/requests/" + request + "/erase", admin.accessToken(), """
                {"confirmAdmissionNo":"P-1"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.confirmAdmissionNo").exists());
        api.post("/api/privacy/requests/" + request + "/erase", admin.accessToken(), """
                {"confirmAdmissionNo":"P-3"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("left the school")));
        api.get("/api/privacy/requests/" + request, admin.accessToken())
                .andExpect(jsonPath("$.canErase").value(false))
                .andExpect(jsonPath("$.studentStatus").value("ACTIVE"));
        // It cannot be closed as completed before the data is erased.
        api.post("/api/privacy/requests/" + request + "/close", admin.accessToken(), """
                {"resolution":"COMPLETED"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.resolution").exists());
        // Only erasure requests erase.
        api.post("/api/privacy/requests/" + access + "/erase", admin.accessToken(), """
                {"confirmAdmissionNo":"P-3"}""").andExpect(status().isConflict());

        api.post("/api/students/" + kabir + "/leave", admin.accessToken(), """
                {"status":"TRANSFERRED","leftOn":"%s","reason":"Family moved to Pune"}""".formatted(today()))
                .andExpect(status().isOk());
        api.get("/api/privacy/requests/" + request, admin.accessToken())
                .andExpect(jsonPath("$.canErase").value(true));

        // Another school's staff cannot erase it, even knowing the ids.
        School other = api.signup();
        Session outsider = api.login(other);
        api.post("/api/privacy/requests/" + request + "/erase", outsider.accessToken(), """
                {"confirmAdmissionNo":"P-3"}""").andExpect(status().isNotFound());

        api.post("/api/privacy/requests/" + request + "/erase", admin.accessToken(), """
                {"confirmAdmissionNo":" p-3 "}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedAt").exists())
                .andExpect(jsonPath("$.canErase").value(false))
                .andExpect(jsonPath("$.studentName").value("Erased student"))
                .andExpect(jsonPath("$.events[-1].kind").value("ERASED"))
                .andExpect(jsonPath("$.events[-1].body", containsString("1 fee receipt(s) kept until")));
        api.post("/api/privacy/requests/" + request + "/erase", admin.accessToken(), """
                {"confirmAdmissionNo":"P-3"}""").andExpect(status().isConflict());

        // The student row stays with its admission number; personal fields are gone, and so is the family's record.
        api.get("/api/students/" + kabir, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admissionNo").value("P-3"))
                .andExpect(jsonPath("$.fullName").value("Erased student"))
                .andExpect(jsonPath("$.lastName").doesNotExist())
                .andExpect(jsonPath("$.dateOfBirth").value("2016-01-01"))
                .andExpect(jsonPath("$.leavingReason").doesNotExist())
                .andExpect(jsonPath("$.status").value("TRANSFERRED"))
                .andExpect(jsonPath("$.guardians.length()").value(0));
        assertThat(guardiansNamed("Farah Khan")).isZero();
        // The receipt is kept unchanged for the legal retention period.
        api.get("/api/fees/receipts/" + receipt, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountPaise").value(11_500_00))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                // A receipt is a financial record: it keeps the name printed on it.
                .andExpect(jsonPath("$.studentName").value("Kabir Khan"));
        // The export made for the family is deleted with the data.
        api.get("/api/privacy/requests/" + access, admin.accessToken())
                .andExpect(jsonPath("$.export").doesNotExist())
                .andExpect(jsonPath("$.exports[0].status").value("ERASED"));
        // Consent history stays as proof of what was agreed.
        api.get("/api/privacy/students/" + kabir + "/consents", admin.accessToken())
                .andExpect(jsonPath("$.history.length()").value(3));

        // The other family is untouched.
        api.get("/api/students/" + arjun, admin.accessToken())
                .andExpect(jsonPath("$.fullName").value("Arjun Sharma"))
                .andExpect(jsonPath("$.guardians[0].phone").value(ANITHA_PHONE));

        api.post("/api/privacy/requests/" + request + "/close", admin.accessToken(), """
                {"resolution":"COMPLETED","note":"Kabir's personal details have been erased."}""")
                .andExpect(status().isOk());
        api.get("/api/audit-events?limit=100", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("data_request.erased", "student.anonymised")));
        List<StudentDataErased> erased = events.stream(StudentDataErased.class).toList();
        assertThat(erased).hasSize(1);
        assertThat(erased.getFirst().studentId()).isEqualTo(UUID.fromString(kabir));
        assertThat(erased.getFirst().tenantId()).isEqualTo(school.tenantId());
        assertThat(erased.getFirst().unlinkedUserIds()).hasSize(1);
    }

    @Test
    void aSharedParentRecordIsKeptForTheSiblingAndTheAdmissionRecordIsAnonymised() throws Exception {
        // Diya came through admissions; her mother also has Arjun at the school.
        String application = TestApi.read(api.post("/api/admissions/applications", admin.accessToken(), """
                {"stage":"APPLICATION","firstName":"Meera","lastName":"Sharma","dateOfBirth":"2020-07-15",
                 "previousSchool":"Little Steps","classId":"%s","academicYearId":"%s","source":"WALK_IN",
                 "note":"Mother says Meera has a peanut allergy.",
                 "guardians":[{"name":"Anitha Sharma","relation":"MOTHER","phone":"%s","primary":true},
                              {"name":"Vikram Sharma","relation":"FATHER","phone":"9876500002"}]}"""
                .formatted(classId, yearId, ANITHA_PHONE)).andExpect(status().isCreated()), "$.id");
        api.post("/api/admissions/applications/" + application + "/stage", admin.accessToken(), """
                {"stage":"OFFERED"}""").andExpect(status().isOk());
        String meera = TestApi.read(api.post("/api/admissions/applications/" + application + "/admit",
                admin.accessToken(), """
                {"sectionId":"%s","admissionNo":"P-9","gender":"FEMALE"}""".formatted(sectionId))
                .andExpect(status().isOk()), "$.studentId");
        api.get("/api/me/privacy", parent.accessToken())
                .andExpect(jsonPath("$.children.length()").value(3));

        api.get("/api/admissions/applications/" + application, admin.accessToken())
                .andExpect(jsonPath("$.timeline[-1].note").value("Mother says Meera has a peanut allergy."));
        String request = submit(parent, "ERASURE", "CHILD", meera, null);
        api.post("/api/students/" + meera + "/leave", admin.accessToken(), """
                {"status":"WITHDRAWN","leftOn":"%s","reason":"Admission cancelled"}""".formatted(today()))
                .andExpect(status().isOk());
        api.post("/api/privacy/requests/" + request + "/erase", admin.accessToken(), """
                {"confirmAdmissionNo":"P-9"}""").andExpect(status().isOk());

        // Anitha keeps her record and sign-in for Arjun and Diya; the father, only on Meera's record, is deleted.
        api.get("/api/me/privacy", parent.accessToken())
                .andExpect(jsonPath("$.children.length()").value(2));
        api.get("/api/students/" + arjun, admin.accessToken())
                .andExpect(jsonPath("$.guardians.length()").value(1))
                .andExpect(jsonPath("$.guardians[0].hasSignIn").value(true));
        assertThat(guardiansNamed("Vikram Sharma")).isZero();
        assertThat(guardiansNamed("Anitha Sharma")).isEqualTo(1);
        // The application keeps its stages and dates; the child's details, the family's contacts and notes go.
        api.get("/api/admissions/applications/" + application, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("ADMITTED"))
                .andExpect(jsonPath("$.childName").value("Erased student"))
                .andExpect(jsonPath("$.dateOfBirth").value("2020-01-01"))
                .andExpect(jsonPath("$.previousSchool").doesNotExist())
                .andExpect(jsonPath("$.guardians.length()").value(0))
                .andExpect(jsonPath("$.timeline[-1].kind").value("CREATED"))
                .andExpect(jsonPath("$.timeline[*].note", everyItem(nullValue())));
        assertThat(events.stream(StudentDataErased.class).toList().getFirst().unlinkedUserIds()).isEmpty();
        api.get("/api/audit-events?limit=100", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("application.anonymised")));
    }

    private long guardiansNamed(String name) throws Exception {
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement(
                        "select count(*) from students.guardian where tenant_id = ? and name = ?")) {
            s.setObject(1, school.tenantId());
            s.setString(2, name);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
