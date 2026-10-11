package com.akshara.exams;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.akshara.academics.AcademicsDirectory.YearInfo;

/** Response bodies of the exams API (docs/api/phase-2-exams.md). */
public final class ExamsViews {

    private ExamsViews() {
    }

    public record YearRef(UUID id, String name, LocalDate startsOn, LocalDate endsOn, boolean current) {

        static YearRef of(YearInfo y) {
            return new YearRef(y.id(), y.name(), y.startsOn(), y.endsOn(), y.current());
        }
    }

    public record ClassRef(UUID id, String name) {
    }

    public record SubjectRef(UUID id, String name, String code) {
    }

    // ------------------------------------------------------------------ grading

    public record Band(BigDecimal minPercent, String grade, BigDecimal gradePoint, String remark) {
    }

    /** {@code builtIn} for the schemes the product adds (they keep their code); {@code classCount} uses this year. */
    public record Scheme(UUID id, String code, String name, SchemeKind kind, String description,
            BigDecimal passPercent, BigDecimal overallPassPercent, boolean builtIn, List<Band> bands, long inUse,
            Instant updatedAt) {
    }

    public record Settings(boolean ranksEnabled, int hpcUpToLevel, UUID coScholasticSchemeId,
            String coScholasticSchemeName) {
    }

    /** {@code level} is read from the class name (LKG and UKG are 0); {@code hpc}: gets the holistic progress card. */
    public record ClassSchemeRow(UUID classId, String className, Integer level, UUID schemeId, String schemeName,
            boolean defaultScheme, boolean hpc) {
    }

    public record ClassSchemes(YearRef year, List<YearRef> years, String board, List<ClassSchemeRow> classes) {
    }

    // ------------------------------------------------------------------ structure

    public record ExamClassSummary(UUID examClassId, UUID classId, String className, int papers, int papersScheduled,
            Instant dateSheetPublishedAt, Instant resultsPublishedAt) {
    }

    public record ExamSummary(UUID id, String name, BigDecimal weight, String instructions, LocalDate firstPaperOn,
            LocalDate lastPaperOn, List<ExamClassSummary> classes) {
    }

    public record TermView(UUID id, String name, LocalDate startsOn, LocalDate endsOn, BigDecimal weight,
            BigDecimal examWeightTotal, List<ExamSummary> exams) {
    }

    /** A year's terms and exams. {@code years} newest first; {@code year} null when the school has none. */
    public record Structure(YearRef year, List<YearRef> years, List<TermView> terms, BigDecimal termWeightTotal,
            List<ClassRef> classes, boolean canSetup, boolean canPublish) {
    }

    // ------------------------------------------------------------------ papers and date sheets

    public record PaperView(UUID id, UUID subjectId, String subjectName, String subjectCode, BigDecimal maxTheory,
            BigDecimal maxPractical, BigDecimal maxInternal, BigDecimal maxTotal, LocalDate date, LocalTime startTime,
            Integer durationMinutes, LocalTime endTime, String room, boolean hasMarks) {
    }

    /** A problem on a date sheet: OVERLAP (two papers of the class at once), HOLIDAY or SUNDAY. */
    public record Clash(UUID paperId, String kind, String detail, UUID otherPaperId) {
    }

    public record ExamClassDetail(UUID examId, String examName, UUID termId, String termName, UUID academicYearId,
            String academicYearName, UUID examClassId, UUID classId, String className, String instructions,
            List<PaperView> papers, List<Clash> clashes, List<SubjectRef> classSubjects,
            Instant dateSheetPublishedAt, String dateSheetPublishedBy, Instant resultsPublishedAt,
            String resultsPublishedBy, boolean canSetup, boolean canPublish) {
    }

    public record SchoolHeader(String name, String code, String board, String city, String address, String phone) {
    }

    public record HallTicketStudent(UUID studentId, String fullName, String admissionNo, Integer rollNo,
            UUID sectionId, String sectionName, String sectionLabel) {
    }

    public record HallTickets(SchoolHeader school, UUID examId, String examName, String termName,
            String academicYearName, UUID classId, String className, String instructions, List<PaperView> papers,
            List<HallTicketStudent> students) {
    }

    // ------------------------------------------------------------------ marks

    /** One paper for one section on the progress list. {@code status}: NOT_STARTED, DRAFT or LOCKED. */
    public record SheetRow(UUID examClassId, UUID classId, String className, UUID paperId, UUID subjectId,
            String subjectName, UUID sectionId, String sectionLabel, LocalDate paperDate, String status, int students,
            int entered, boolean canEdit, String updatedByName, Instant updatedAt, boolean resultsPublished) {
    }

    public record ExamMarks(UUID examId, String examName, String termName, int total, int locked, int draft,
            int notStarted, List<SheetRow> sheets) {
    }

    public record MarksOverview(YearRef year, List<YearRef> years, boolean wholeSchool, List<ExamMarks> exams) {
    }

    /** {@code inSection} false for a student with marks who has since left the section. */
    public record MarkRow(UUID studentId, String fullName, String admissionNo, Integer rollNo, boolean inSection,
            BigDecimal theory, BigDecimal practical, BigDecimal internal, MarkCode code, BigDecimal total,
            BigDecimal percent, String grade) {
    }

    public record MarkSheetView(UUID sheetId, Long version, UUID examId, String examName, String termName,
            UUID examClassId, UUID classId, String className, UUID sectionId, String sectionLabel, UUID paperId,
            UUID subjectId, String subjectName, BigDecimal maxTheory, BigDecimal maxPractical, BigDecimal maxInternal,
            BigDecimal maxTotal, LocalDate paperDate, SheetStatus status, String updatedByName, Instant updatedAt,
            String submittedByName, Instant submittedAt, String unlockedByName, Instant unlockedAt,
            String unlockReason, boolean canEdit, boolean canUnlock, boolean resultsPublished, int entered,
            List<MarkRow> students) {
    }

    // ------------------------------------------------------------------ results

    public record SchemeRef(UUID id, String name, BigDecimal passPercent, BigDecimal overallPassPercent,
            boolean gradePoints, List<Band> bands) {
    }

    public record SubjectHead(UUID paperId, UUID subjectId, String subjectName, BigDecimal max) {
    }

    /** {@code passed} null when there is nothing to judge (ML, EX, or marks missing). */
    public record SubjectScore(UUID subjectId, BigDecimal obtained, BigDecimal max, BigDecimal percent, String grade,
            BigDecimal gradePoint, Boolean passed, MarkCode code) {
    }

    public record ResultRow(UUID studentId, String fullName, String admissionNo, Integer rollNo,
            List<SubjectScore> subjects, BigDecimal obtained, BigDecimal max, BigDecimal percent, String grade,
            BigDecimal gpa, OverallResult result, Integer rank) {
    }

    public record SectionResults(UUID examId, String examName, String termName, UUID examClassId, UUID classId,
            String className, UUID sectionId, String sectionLabel, SchemeRef scheme, boolean ranksEnabled,
            Instant resultsPublishedAt, int sheetsPending, List<SubjectHead> subjects, List<ResultRow> rows) {
    }

    /** Levels per domain and the three reflections of the holistic progress card. */
    public record Hpc(HpcLevel physical, HpcLevel socioEmotional, HpcLevel cognitive, HpcLevel language,
            HpcLevel numeracy, HpcLevel aesthetic, String teacherReflection, String selfReflection,
            String parentReflection) {

        static final Hpc EMPTY = new Hpc(null, null, null, null, null, null, null, null, null);

        boolean empty() {
            return equals(EMPTY);
        }
    }

    public record CoScholastic(String workEducation, String artEducation, String healthPhysical, String discipline) {
    }

    public record StudentRecord(UUID studentId, String fullName, String admissionNo, Integer rollNo,
            CoScholastic coScholastic, String remark, Hpc hpc) {
    }

    public record TermRecords(UUID termId, String termName, UUID classId, String className, UUID sectionId,
            String sectionLabel, List<String> coScholasticGrades, boolean hpcEnabled, boolean canEdit,
            List<StudentRecord> students) {
    }

    // ------------------------------------------------------------------ report cards

    /** A column of the scholastic table: an exam (term card) or a term (year card). */
    public record CardColumn(UUID id, String name, BigDecimal weight) {
    }

    public record CardCell(UUID columnId, BigDecimal obtained, BigDecimal max, BigDecimal percent, String grade,
            MarkCode code) {
    }

    public record CardSubject(UUID subjectId, String subjectName, List<CardCell> cells, BigDecimal percent,
            String grade, BigDecimal gradePoint, Boolean passed) {
    }

    public record CardAttendance(LocalDate from, LocalDate to, int daysMarked, int present, int absent, int late,
            int halfDay, int leave, Double presentPercent) {
    }

    public record ReportCard(UUID studentId, String fullName, String admissionNo, Integer rollNo, String classLabel,
            List<CardSubject> subjects, BigDecimal percent, String grade, BigDecimal gpa, OverallResult result,
            Integer rank, CardAttendance attendance, CoScholastic coScholastic, String remark, Hpc hpc) {
    }

    /**
     * Report cards of a section (or one student) for a term ({@code kind} TERM, columns are the term's exams) or the
     * year (YEAR, columns are the terms). {@code publishedOnly}: only published results count (families).
     */
    public record ReportCards(SchoolHeader school, String kind, UUID academicYearId, String academicYearName,
            UUID termId, String termName, UUID classId, String className, UUID sectionId, String sectionLabel,
            String classTeacherName, SchemeRef scheme, boolean ranksEnabled, boolean hpcEnabled,
            boolean publishedOnly, List<CardColumn> columns, List<ReportCard> cards) {
    }

    // ------------------------------------------------------------------ families

    public record FamilyExam(UUID examId, String examName, UUID termId, String termName, Instant publishedAt,
            BigDecimal percent, String grade, OverallResult result) {
    }

    public record FamilyTerm(UUID id, String name, boolean published) {
    }

    public record UpcomingExam(UUID examId, String examName, String termName, List<PaperView> papers) {
    }

    public record FamilyResults(UUID studentId, String fullName, String classLabel, YearRef year,
            List<FamilyTerm> terms, List<FamilyExam> results, List<UpcomingExam> upcoming) {
    }

    // ------------------------------------------------------------------ analysis

    public record GradeCount(String grade, int count) {
    }

    /** A section's figures: overall (in {@link Analysis#sections()}) or in one subject. */
    public record SectionStat(UUID sectionId, String label, int students, int appeared, BigDecimal average,
            int passed, BigDecimal passPercent) {
    }

    /**
     * One subject of an exam for a class: marks out of {@code max} (average, highest, lowest) of the students who
     * wrote it ({@code appeared}; {@code absent} were marked AB), the average as a percentage, pass counts among those
     * who wrote it, the grade distribution and the change since the previous exam's average percentage.
     */
    public record SubjectStat(UUID subjectId, String subjectName, BigDecimal max, int appeared, int absent,
            BigDecimal averageMarks, BigDecimal highest, BigDecimal lowest, BigDecimal average, int passed,
            BigDecimal passPercent, List<GradeCount> grades, BigDecimal previousAverage, BigDecimal change,
            List<SectionStat> sections) {
    }

    public record WeakSubject(String subjectName, BigDecimal percent, MarkCode code) {
    }

    /** A student who failed a subject or the exam overall; {@code percent} overall. */
    public record BelowPass(UUID studentId, String fullName, String admissionNo, Integer rollNo, String sectionLabel,
            BigDecimal percent, List<WeakSubject> subjects) {
    }

    /**
     * Result analysis of an exam for a class, from the marks entered so far ({@code sheetsPending} not locked yet).
     * {@code appeared}: students with a complete result; averages are percentages; {@code previousExam*}: the exam
     * held before it in the year for the class, scored for the same students.
     */
    public record Analysis(UUID examId, String examName, String termName, UUID academicYearId, UUID classId,
            String className, UUID previousExamId, String previousExamName, SchemeRef scheme, int students,
            int appeared, BigDecimal average, int passed, BigDecimal passPercent, BigDecimal previousAverage,
            List<GradeCount> grades,
            List<SubjectStat> subjects, List<SectionStat> sections, List<BelowPass> belowPass,
            Instant resultsPublishedAt, int sheetsPending) {
    }
}
