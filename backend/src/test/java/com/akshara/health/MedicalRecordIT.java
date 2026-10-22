package com.akshara.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/** Medical records: who may read (audited), who may edit, the masking, the growth chart, and the family app. */
class MedicalRecordIT extends IntegrationTest {

    @Test
    void theNurseKeepsTheRecordAndEveryStaffReadIsAudited() throws Exception {
        HealthSchool s = new HealthSchool(api);
        String nurse = s.nurse.accessToken();
        // An empty record is readable; the search finds the student.
        api.get("/api/health/students?q=asha", nurse).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Asha Rao")).andExpect(jsonPath("$[0].kind").value("STUDENT"));
        api.get("/api/health/students/" + s.asha + "/record?screen=student_medical", nurse)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.emergencyContact.name").value("Lata Rao"))
                .andExpect(jsonPath("$.emergencyContact.phoneMasked").value("98765•••01"))
                .andExpect(jsonPath("$.consent.firstAid").value(false));
        api.put("/api/health/students/" + s.asha + "/record", nurse, """
                {"bloodGroup":"XX"}""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.bloodGroup").exists());
        api.put("/api/health/students/" + s.asha + "/record", nurse, """
                {"bloodGroup":"O+","doctorName":"Dr Rao","doctorPhone":"98765 43210","insuranceProvider":"Star",
                 "insurancePolicyNo":"POL-2026-778812","notes":"Wears glasses"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.bloodGroup").value("O+"))
                .andExpect(jsonPath("$.doctorPhone").value("9876543210"))
                .andExpect(jsonPath("$.insurancePolicyNoMasked").value("••••8812"))
                .andExpect(jsonPath("$.insurancePolicyNo").doesNotExist());
        api.put("/api/health/students/" + s.asha + "/record/consent", nurse, """
                {"firstAid":true,"hospital":false}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.consent.firstAid").value(true))
                .andExpect(jsonPath("$.consent.byName").value("Mary Thomas"));
        // Items: an allergy needs a severity, a vaccination a date; the kind never changes.
        api.post("/api/health/students/" + s.asha + "/record/items", nurse, """
                {"kind":"ALLERGY","name":"Peanuts"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.severity").exists());
        api.post("/api/health/students/" + s.asha + "/record/items", nurse, """
                {"kind":"VACCINATION","name":"MMR"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.itemDate").exists());
        String allergy = TestApi.read(api.post("/api/health/students/" + s.asha + "/record/items", nurse, """
                {"kind":"ALLERGY","name":"Peanuts","severity":"SEVERE","detail":"EpiPen in bag"}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.source").value("STAFF")), "$.id");
        api.put("/api/health/students/" + s.asha + "/record/items/" + allergy, nurse, """
                {"kind":"CONDITION","name":"Peanuts","severity":"SEVERE"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.kind").exists());
        api.post("/api/health/students/" + s.asha + "/record/items", nurse, """
                {"kind":"MEDICATION","name":"Salbutamol inhaler","detail":"When wheezing"}""")
                .andExpect(status().isCreated());
        // Growth: height or weight, not in the future; a second measurement on the same day replaces the first.
        api.post("/api/health/students/" + s.asha + "/record/growth", nurse, """
                {"measuredOn":"2026-06-10"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.heightCm").exists());
        api.post("/api/health/students/" + s.asha + "/record/growth", nurse, """
                {"measuredOn":"2099-01-01","heightCm":120}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.measuredOn").exists());
        api.post("/api/health/students/" + s.asha + "/record/growth", nurse, """
                {"measuredOn":"2026-06-10","heightCm":128.04,"weightKg":25}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.heightCm").value(128.0));
        api.post("/api/health/students/" + s.asha + "/record/growth", nurse, """
                {"measuredOn":"2026-06-10","heightCm":128.5,"weightKg":25.5}""").andExpect(status().isCreated());
        api.post("/api/health/students/" + s.asha + "/record/growth", nurse, """
                {"measuredOn":"2026-09-10","heightCm":130,"weightKg":26.5}""").andExpect(status().isCreated());
        String body = api.get("/api/health/students/" + s.asha + "/record?screen=student_medical", nurse)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allergies.length()").value(1))
                .andExpect(jsonPath("$.medications.length()").value(1))
                .andExpect(jsonPath("$.growth.length()").value(2))
                .andExpect(jsonPath("$.chart.points.length()").value(2))
                .andExpect(jsonPath("$.chart.points[0].ageMonths").value(121))
                .andExpect(jsonPath("$.chart.maxHeight").value(130.0))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("POL-2026-778812");
        // Every staff read is audited with the screen; writes too.
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("medical_record.viewed", "medical_record.updated",
                        "medical_record.consent_updated", "medical_record_item.added", "growth_measurement.added")))
                .andExpect(jsonPath("$[?(@.action == 'medical_record.viewed')].details.screen",
                        hasItem("student_medical")));
        // A teacher without health.manage cannot edit.
        api.put("/api/health/students/" + s.asha + "/record", s.teacher.accessToken(), """
                {"bloodGroup":"A+"}""").andExpect(status().isForbidden());
        api.delete("/api/health/students/" + s.asha + "/record/items/" + allergy, s.teacher.accessToken())
                .andExpect(status().isForbidden());
        api.delete("/api/health/students/" + s.asha + "/record/items/" + allergy, nurse)
                .andExpect(status().isNoContent());
        api.delete("/api/health/students/" + s.asha + "/record/items/" + allergy, nurse)
                .andExpect(status().isNotFound());
    }

    @Test
    void classTeachersReadOnlyTheirOwnSectionsAndOtherSchoolsSeeNothing() throws Exception {
        HealthSchool s = new HealthSchool(api);
        // Tara leads 5 A: Asha yes, Chitra (6 A) no. The principal and admin read everyone.
        api.get("/api/health/students/" + s.asha + "/record?screen=class_list", s.teacher.accessToken())
                .andExpect(status().isOk());
        api.get("/api/health/students/" + s.chitra + "/record", s.teacher.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/health/students/" + s.asha + "/record", s.otherTeacher.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/health/students/" + s.chitra + "/record", s.principal.accessToken()).andExpect(status().isOk());
        api.get("/api/health/students/" + s.chitra + "/record", s.admin.accessToken()).andExpect(status().isOk());
        // A parent's sign-in has no health.read.
        api.get("/api/health/students/" + s.asha + "/record", s.parent.accessToken()).andExpect(status().isForbidden());
        // Another school's ids are 404 in paths.
        HealthSchool other = new HealthSchool(api);
        api.get("/api/health/students/" + s.asha + "/record", other.nurse.accessToken())
                .andExpect(status().isNotFound());
        api.put("/api/health/students/" + s.asha + "/record", other.nurse.accessToken(), """
                {"bloodGroup":"A+"}""").andExpect(status().isNotFound());
        api.get("/api/health/students/" + UUID.randomUUID() + "/record", s.nurse.accessToken())
                .andExpect(status().isNotFound());
        api.get("/api/health/students?q=asha", other.nurse.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + s.asha + "')]").doesNotExist());
    }

    @Test
    void parentsKeepAllergiesConditionsAndConsentForTheirOwnChildOnly() throws Exception {
        HealthSchool s = new HealthSchool(api);
        String parent = s.parent.accessToken();
        api.get("/api/me/children/" + s.asha + "/health", parent).andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.visits.length()").value(0));
        api.get("/api/me/children/" + s.bala + "/health", parent).andExpect(status().isNotFound());
        api.put("/api/me/children/" + s.bala + "/health/consent", parent, """
                {"firstAid":true,"hospital":true}""").andExpect(status().isNotFound());
        api.put("/api/me/children/" + s.asha + "/health/consent", parent, """
                {"firstAid":true,"hospital":true}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.consent.hospital").value(true))
                .andExpect(jsonPath("$.consent.byName").value("Lata Rao"));
        String allergy = TestApi.read(api.post("/api/me/children/" + s.asha + "/health/items", parent, """
                {"kind":"ALLERGY","name":"Peanuts","severity":"MODERATE"}""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("PARENT")), "$.id");
        api.post("/api/me/children/" + s.asha + "/health/items", parent, """
                {"kind":"CONDITION","name":"Asthma","detail":"Inhaler in bag"}""").andExpect(status().isCreated());
        // Medication and vaccinations are the school's.
        api.post("/api/me/children/" + s.asha + "/health/items", parent, """
                {"kind":"MEDICATION","name":"Inhaler"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.kind").exists());
        api.put("/api/me/children/" + s.asha + "/health/items/" + allergy, parent, """
                {"kind":"ALLERGY","name":"Peanuts","severity":"SEVERE"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("SEVERE"));
        // The parent's own reads are not audited; their writes are.
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("medical_record_item.added", "medical_record_item.updated",
                        "medical_record.consent_updated")))
                .andExpect(jsonPath("$[*].action", not(hasItem("medical_record.viewed"))));
        // The nurse sees the parent's entries and the badge shows up for other modules' callers.
        api.get("/api/health/students/" + s.asha + "/record?screen=infirmary", s.nurse.accessToken())
                .andExpect(jsonPath("$.allergies[0].name").value("Peanuts"))
                .andExpect(jsonPath("$.allergies[0].severity").value("SEVERE"))
                .andExpect(jsonPath("$.allergies[0].source").value("PARENT"))
                .andExpect(jsonPath("$.conditions[0].name").value("Asthma"));
        api.delete("/api/me/children/" + s.asha + "/health/items/" + allergy, parent).andExpect(status().isNoContent());
        api.get("/api/audit-events?limit=50", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("medical_record_item.removed", "medical_record.viewed")));
        // A teacher's sign-in is not a parent.
        api.get("/api/me/children/" + s.asha + "/health", s.teacher.accessToken()).andExpect(status().isForbidden());
    }
}
