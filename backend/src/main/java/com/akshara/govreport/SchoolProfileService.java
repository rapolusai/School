package com.akshara.govreport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.govreport.GovReportForms.ProfileForm;
import com.akshara.govreport.GovReportViews.Completeness;
import com.akshara.govreport.GovReportViews.ProfileView;
import com.akshara.govreport.GovReportViews.TeacherCounts;
import com.akshara.platform.SchoolProfile;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffService;
import com.akshara.staff.StaffService.StaffDetail;
import com.akshara.staff.StaffService.StaffQuery;
import com.akshara.staff.StaffService.StaffRow;
import com.akshara.staff.StaffStatus;

/**
 * The school's government profile, one record per academic year, with a completeness check listing what the UDISE+
 * school profile still needs, and teacher counts by qualification derived from the staff profiles.
 */
@Service
@Transactional
public class SchoolProfileService {

    /** The fields the UDISE+ school profile and the OASIS data sheet need, in the order the screen shows them. */
    static final List<String> REQUIRED = List.of("udiseCode", "board", "affiliationNo", "schoolCategoryCode",
            "managementCode", "diseBlockCode", "diseDistrictCode", "stateCode", "recognitionAuthority",
            "recognitionNo", "lowestClass", "highestClass", "mediumOfInstruction", "classrooms", "toiletsBoys",
            "toiletsGirls", "toiletsCwsn", "drinkingWater", "electricity", "library", "playground", "computers",
            "internet", "ramps", "boundaryWall");

    private final GovSchoolProfileRepository profiles;
    private final AcademicsDirectory academics;
    private final TenantDirectory tenants;
    private final StaffService staff;
    private final AuditService audit;

    SchoolProfileService(GovSchoolProfileRepository profiles, AcademicsDirectory academics, TenantDirectory tenants,
            StaffService staff, AuditService audit) {
        this.profiles = profiles;
        this.academics = academics;
        this.tenants = tenants;
        this.staff = staff;
        this.audit = audit;
    }

    /** The year asked for, or the current one. 400 when the school has no current year. */
    YearInfo year(UUID yearId) {
        TenantContext.require();
        if (yearId != null) {
            return academics.year(yearId).orElseThrow(() -> ApiException.notFound("Academic year"));
        }
        return academics.currentYear()
                .orElseThrow(() -> ApiException.badRequest("Set a current academic year first.", "yearId"));
    }

    @Transactional
    public ProfileView profile(UUID yearId) {
        YearInfo year = year(yearId);
        GovSchoolProfile p = profiles.findByAcademicYearId(year.id()).orElse(null);
        return view(year, p, teacherCounts());
    }

    public ProfileView save(UUID yearId, ProfileForm form, Actor actor) {
        YearInfo year = year(yearId);
        GovSchoolProfile p = profiles.findByAcademicYearId(year.id()).orElseGet(() -> new GovSchoolProfile(year.id()));
        if (form.affiliationValidUntil() != null && form.recognitionValidUntil() != null
                && form.recognitionValidUntil().isBefore(year.startsOn().minusYears(10))) {
            throw ApiException.badRequest("Check the recognition date.", "recognitionValidUntil");
        }
        p.update(form);
        profiles.saveAndFlush(p);
        ProfileView view = view(year, p, teacherCounts());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("academicYear", year.name());
        details.put("udiseCode", p.getUdiseCode());
        details.put("completeness", view.completeness().percent());
        details.put("missing", view.completeness().missing());
        audit.record(actor, "govreport_profile.updated", "govreport_profile", p.getId(), details);
        return view;
    }

    /** The saved profile of a year, if any (for the exports). */
    GovSchoolProfile saved(UUID yearId) {
        return profiles.findByAcademicYearId(yearId).orElse(null);
    }

    /** What is filled in of what the export needs. */
    static Completeness completeness(GovSchoolProfile p) {
        List<String> missing = new ArrayList<>();
        if (p == null) {
            missing.addAll(REQUIRED);
        } else {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("udiseCode", p.getUdiseCode());
            values.put("board", p.getBoard());
            values.put("affiliationNo", p.getAffiliationNo());
            values.put("schoolCategoryCode", p.getSchoolCategoryCode());
            values.put("managementCode", p.getManagementCode());
            values.put("diseBlockCode", p.getDiseBlockCode());
            values.put("diseDistrictCode", p.getDiseDistrictCode());
            values.put("stateCode", p.getStateCode());
            values.put("recognitionAuthority", p.getRecognitionAuthority());
            values.put("recognitionNo", p.getRecognitionNo());
            values.put("lowestClass", p.getLowestClass());
            values.put("highestClass", p.getHighestClass());
            values.put("mediumOfInstruction", p.getMediumOfInstruction());
            values.put("classrooms", p.getClassrooms());
            values.put("toiletsBoys", p.getToiletsBoys());
            values.put("toiletsGirls", p.getToiletsGirls());
            values.put("toiletsCwsn", p.getToiletsCwsn());
            values.put("drinkingWater", p.getDrinkingWater());
            values.put("electricity", p.getElectricity());
            values.put("library", p.getLibrary());
            values.put("playground", p.getPlayground());
            values.put("computers", p.getComputers());
            values.put("internet", p.getInternet());
            values.put("ramps", p.getRamps());
            values.put("boundaryWall", p.getBoundaryWall());
            for (String field : REQUIRED) {
                if (values.get(field) == null) {
                    missing.add(field);
                }
            }
        }
        int total = REQUIRED.size();
        int filled = total - missing.size();
        return new Completeness(filled, total, total == 0 ? 100 : (int) Math.floor(filled * 100.0 / total), missing);
    }

    /**
     * Teacher counts from the staff directory: everyone with the Teacher role who is on the staff today, counted as
     * trained (B.Ed, M.Ed, D.El.Ed, B.El.Ed, TTC or NTT in their qualifications), post graduate (M.A, M.Sc, M.Com,
     * MCA or "post graduate"), graduate (B.A, B.Sc, B.Com, BCA, "graduate") or other. Best effort from free text:
     * the export marks it "verify".
     */
    @Transactional
    public TeacherCounts teacherCounts() {
        TenantContext.require();
        long total = 0;
        long trained = 0;
        long pg = 0;
        long ug = 0;
        long other = 0;
        long incomplete = 0;
        int page = 0;
        while (true) {
            List<StaffRow> rows = staff.list(new StaffQuery(null, null, "TEACHER", StaffStatus.ACTIVE, null, null,
                    page, StaffService.MAX_PAGE_SIZE)).items();
            for (StaffRow row : rows) {
                total++;
                if (!row.profileComplete()) {
                    incomplete++;
                    other++;
                    continue;
                }
                StaffDetail detail = staff.detail(row.userId());
                String q = detail.profile() == null || detail.profile().qualifications() == null ? ""
                        : detail.profile().qualifications().toUpperCase(Locale.ROOT);
                if (isTrained(q)) {
                    trained++;
                }
                if (isPostGraduate(q)) {
                    pg++;
                } else if (isGraduate(q)) {
                    ug++;
                } else {
                    other++;
                }
            }
            if (rows.size() < StaffService.MAX_PAGE_SIZE) {
                break;
            }
            page++;
        }
        return new TeacherCounts(total, trained, pg, ug, other, incomplete);
    }

    static boolean isTrained(String q) {
        String s = q.replace(".", "").replace(" ", "");
        return s.contains("BED") || s.contains("MED") || s.contains("DELED") || s.contains("BELED")
                || s.contains("TTC") || s.contains("NTT") || s.contains("DED");
    }

    static boolean isPostGraduate(String q) {
        String s = q.replace(".", "").replace(" ", "");
        return s.contains("POSTGRADUATE") || s.matches(".*\\bM(A|SC|COM|CA|TECH|PHIL)\\b.*") || s.contains("MA,")
                || s.startsWith("MA") || s.contains("MSC") || s.contains("MCOM") || s.contains("MCA")
                || s.contains("MTECH") || s.contains("MPHIL") || s.contains("PHD");
    }

    static boolean isGraduate(String q) {
        String s = q.replace(".", "").replace(" ", "");
        return s.contains("GRADUATE") || s.startsWith("BA") || s.contains("BSC") || s.contains("BCOM")
                || s.contains("BCA") || s.contains("BTECH") || s.contains("BE,") || s.contains(",BA")
                || s.contains("/BA");
    }

    private ProfileView view(YearInfo year, GovSchoolProfile p, TeacherCounts teachers) {
        SchoolProfile school = tenants.profile(TenantContext.require()).orElse(null);
        String schoolName = school == null ? null : school.name();
        Completeness completeness = completeness(p);
        if (p == null) {
            return new ProfileView(false, year.id(), year.name(), schoolName,
                    school == null ? null : school.udiseCode(), school == null || school.board() == null ? null
                            : school.board().name(), null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, teachers, completeness, null);
        }
        return new ProfileView(true, year.id(), year.name(), schoolName, p.getUdiseCode(), p.getBoard(),
                p.getAffiliationNo(), p.getAffiliationValidUntil(), p.getSchoolCategoryCode(), p.getManagementCode(),
                p.getDiseBlockCode(), p.getDiseDistrictCode(), p.getStateCode(), p.getRecognitionAuthority(),
                p.getRecognitionNo(), p.getRecognitionValidUntil(), p.getLowestClass(), p.getHighestClass(),
                p.getMediumOfInstruction(), p.getClassrooms(), p.getOtherRooms(), p.getToiletsBoys(),
                p.getToiletsGirls(), p.getToiletsCwsn(), p.getDrinkingWater(), p.getDrinkingWaterSource(),
                p.getElectricity(), p.getLibrary(), p.getLibraryBooks(), p.getPlayground(), p.getComputers(),
                p.getInternet(), p.getRamps(), p.getBoundaryWall(), teachers, completeness, p.getUpdatedAt());
    }
}
