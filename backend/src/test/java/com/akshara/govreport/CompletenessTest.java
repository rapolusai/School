package com.akshara.govreport;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.audit.AuditService.Actor;
import com.akshara.govreport.GovReportForms.ProfileForm;
import com.akshara.govreport.GovReportTypes.UpdatedVia;
import com.akshara.govreport.GovReportViews.Completeness;

/** The completeness rules: which student fields and which profile fields count, and the APAAR lock. */
class CompletenessTest {

    private static final Actor OFFICE = new Actor(UUID.randomUUID(), "Office");

    @Test
    void aStudentWithoutARecordIsMissingEveryRequiredField() {
        assertThat(StudentFieldsService.missing(null)).containsExactlyElementsOf(StudentFieldsService.REQUIRED);
        assertThat(StudentFieldsService.REQUIRED).doesNotContain("previousSchoolUdise");
    }

    @Test
    void cwsnTypeAndPreviousSchoolAreOptionalButTheRestAreRequired() {
        StudentGovRecord r = new StudentGovRecord(UUID.randomUUID());
        r.updateByOffice("123456789012", null, "TS1234", "SC", "HINDU", "TELUGU", false, null, false, null, OFFICE,
                UpdatedVia.OFFICE);
        assertThat(StudentFieldsService.missing(r)).isEmpty();
        assertThat(r.isApaarLocked()).as("an APAAR entered by the office is locked by default").isTrue();
        r.updateByOffice(null, false, null, "SC", null, "TELUGU", null, "LOW_VISION", false, "36011200451", OFFICE,
                UpdatedVia.OFFICE);
        assertThat(StudentFieldsService.missing(r)).containsExactly("apaarId", "pen", "religion", "bpl");
    }

    @Test
    void aParentMayFillTheApaarIdOnceAndNeverOverwriteIt() {
        StudentGovRecord r = new StudentGovRecord(UUID.randomUUID());
        r.updateByFamily("HINDI", "111111111111", OFFICE);
        assertThat(r.getApaarId()).isEqualTo("111111111111");
        r.updateByFamily("HINDI", "222222222222", OFFICE);
        assertThat(r.getApaarId()).isEqualTo("111111111111");
        StudentGovRecord locked = new StudentGovRecord(UUID.randomUUID());
        locked.lockApaar(true, OFFICE);
        locked.updateByFamily("HINDI", "333333333333", OFFICE);
        assertThat(locked.getApaarId()).isNull();
        assertThat(locked.getMotherTongue()).isEqualTo("HINDI");
    }

    @Test
    void theProfileCompletenessCountsTheExportFields() {
        assertThat(SchoolProfileService.completeness(null).percent()).isZero();
        GovSchoolProfile p = new GovSchoolProfile(UUID.randomUUID());
        p.update(new ProfileForm("36011200912", "CBSE", "3630412", LocalDate.of(2030, 3, 31), "CAT3",
                "PVT_UNAIDED", "3601", "3601", "TS", "DSE Telangana", "DSE/1182", null, "LKG", "Class 10", "English",
                24, 6, 8, 8, 2, true, "Municipal", true, true, 2400, true, 30, true, true, true));
        Completeness full = SchoolProfileService.completeness(p);
        assertThat(full.missing()).isEmpty();
        assertThat(full.percent()).isEqualTo(100);
        assertThat(full.total()).isEqualTo(SchoolProfileService.REQUIRED.size());
        p.update(new ProfileForm("", "CBSE", null, null, "CAT3", "PVT_UNAIDED", "3601", "3601", "TS", "DSE", "1",
                null, "LKG", "Class 10", "English", 24, 6, 8, 8, 2, true, null, true, true, 2400, true, 30, true,
                true, null));
        Completeness part = SchoolProfileService.completeness(p);
        assertThat(part.missing()).containsExactly("udiseCode", "affiliationNo", "boundaryWall");
        assertThat(part.percent()).isEqualTo((int) Math.floor((part.total() - 3) * 100.0 / part.total()));
    }

    @Test
    void distanceBandsFollowTheIntakeRule() {
        RteIntake intake = new RteIntake(UUID.randomUUID(), UUID.randomUUID(), OFFICE);
        intake.update(40, 10, "TS", 10_000_000L, new BigDecimal("1"), new BigDecimal("3"), new BigDecimal("6"),
                "[]", null);
        assertThat(intake.distanceBand(new BigDecimal("0.5"))).isEqualTo(1);
        assertThat(intake.distanceBand(new BigDecimal("1"))).isEqualTo(1);
        assertThat(intake.distanceBand(new BigDecimal("2.9"))).isEqualTo(2);
        assertThat(intake.distanceBand(new BigDecimal("6"))).isEqualTo(3);
        assertThat(intake.distanceBand(new BigDecimal("6.1"))).isEqualTo(4);
        assertThat(intake.distanceBand(null)).isEqualTo(4);
    }
}
