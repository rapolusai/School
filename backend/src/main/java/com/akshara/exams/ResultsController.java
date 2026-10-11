package com.akshara.exams;

import static com.akshara.exams.ExamsController.ENTER;
import static com.akshara.exams.ExamsController.PUBLISH;
import static com.akshara.exams.ExamsController.READ;
import static com.akshara.exams.ExamsController.READ_OR_ENTER;
import static com.akshara.exams.ExamsController.actor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.exams.ExamsForms.MarkSheetForm;
import com.akshara.exams.ExamsForms.ReasonForm;
import com.akshara.exams.ExamsForms.RecordsForm;
import com.akshara.exams.ExamsForms.SubmitForm;
import com.akshara.exams.ExamsViews.Analysis;
import com.akshara.exams.ExamsViews.ExamClassDetail;
import com.akshara.exams.ExamsViews.MarkSheetView;
import com.akshara.exams.ExamsViews.MarksOverview;
import com.akshara.exams.ExamsViews.ReportCards;
import com.akshara.exams.ExamsViews.SectionResults;
import com.akshara.exams.ExamsViews.TermRecords;

/**
 * Marks, results, term records, report cards and result analysis for staff. Teachers enter marks for the subjects
 * they teach and, as class teacher, every subject of their section, plus co-scholastic grades, remarks and the
 * holistic progress card (exams.manage); exams.publish covers every section, unlocks submitted marks and publishes
 * results. Results, report cards and analysis are read with exams.read.
 */
@RestController
@RequestMapping("/api/exams")
public class ResultsController {

    private final MarksService marks;
    private final ResultsService results;
    private final RecordsService records;
    private final ReportCardService reportCards;
    private final AnalysisService analysis;

    ResultsController(MarksService marks, ResultsService results, RecordsService records,
            ReportCardService reportCards, AnalysisService analysis) {
        this.marks = marks;
        this.results = results;
        this.records = records;
        this.reportCards = reportCards;
        this.analysis = analysis;
    }

    // ------------------------------------------------------------------ marks

    /** Mark sheets of a year's exams (current year by default): a teacher's own, or every one with exams.publish. */
    @GetMapping("/marks")
    @PreAuthorize(READ_OR_ENTER)
    public MarksOverview marks(@RequestParam(required = false) UUID yearId) {
        return marks.overview(yearId);
    }

    @GetMapping("/papers/{paperId}/sections/{sectionId}/marks")
    @PreAuthorize(READ_OR_ENTER)
    public MarkSheetView sheet(@PathVariable UUID paperId, @PathVariable UUID sectionId) {
        return marks.sheet(paperId, sectionId);
    }

    /** Saves a draft; {@code version} is the sheet's version as read (null for a sheet not saved yet). */
    @PutMapping("/papers/{paperId}/sections/{sectionId}/marks")
    @PreAuthorize(ENTER)
    public MarkSheetView save(@PathVariable UUID paperId, @PathVariable UUID sectionId,
            @Valid @RequestBody MarkSheetForm request) {
        return marks.save(paperId, sectionId, request, actor());
    }

    /** Submits and locks the sheet; every student needs marks or a code. */
    @PostMapping("/papers/{paperId}/sections/{sectionId}/marks/submit")
    @PreAuthorize(ENTER)
    public MarkSheetView submit(@PathVariable UUID paperId, @PathVariable UUID sectionId,
            @Valid @RequestBody SubmitForm request) {
        return marks.submit(paperId, sectionId, request.version(), actor());
    }

    /** Unlocks submitted marks for correction, with the reason (audited). */
    @PostMapping("/papers/{paperId}/sections/{sectionId}/marks/unlock")
    @PreAuthorize(PUBLISH)
    public MarkSheetView unlock(@PathVariable UUID paperId, @PathVariable UUID sectionId,
            @Valid @RequestBody ReasonForm request) {
        return marks.unlock(paperId, sectionId, request.reason(), actor());
    }

    // ------------------------------------------------------------------ results

    @GetMapping("/{examId}/sections/{sectionId}/results")
    @PreAuthorize(READ)
    public SectionResults sectionResults(@PathVariable UUID examId, @PathVariable UUID sectionId) {
        return results.sectionResults(examId, sectionId);
    }

    /** Shows the class's results to parents and students and tells parents; every sheet must be locked. */
    @PostMapping("/{examId}/classes/{classId}/results/publish")
    @PreAuthorize(PUBLISH)
    public ExamClassDetail publish(@PathVariable UUID examId, @PathVariable UUID classId) {
        return results.publish(examId, classId, actor());
    }

    @PostMapping("/{examId}/classes/{classId}/results/withdraw")
    @PreAuthorize(PUBLISH)
    public ExamClassDetail withdraw(@PathVariable UUID examId, @PathVariable UUID classId,
            @Valid @RequestBody ReasonForm request) {
        return results.withdraw(examId, classId, request.reason(), actor());
    }

    // ------------------------------------------------------------------ term records

    /** Co-scholastic grades, the class teacher's remark and the holistic progress card of a section for a term. */
    @GetMapping("/terms/{termId}/sections/{sectionId}/records")
    @PreAuthorize(READ_OR_ENTER)
    public TermRecords records(@PathVariable UUID termId, @PathVariable UUID sectionId) {
        return records.records(termId, sectionId);
    }

    @PutMapping("/terms/{termId}/sections/{sectionId}/records")
    @PreAuthorize(ENTER)
    public TermRecords saveRecords(@PathVariable UUID termId, @PathVariable UUID sectionId,
            @Valid @RequestBody RecordsForm request) {
        return records.save(termId, sectionId, request, actor());
    }

    // ------------------------------------------------------------------ report cards

    /** Report cards of a section for a term ({@code termId}) or the year ({@code yearId}). */
    @GetMapping("/report-cards")
    @PreAuthorize(READ)
    public ReportCards reportCards(@RequestParam UUID sectionId, @RequestParam(required = false) UUID termId,
            @RequestParam(required = false) UUID yearId) {
        return reportCards.section(sectionId, termId, yearId);
    }

    @GetMapping("/report-cards/students/{studentId}")
    @PreAuthorize(READ)
    public ReportCards studentCard(@PathVariable UUID studentId, @RequestParam(required = false) UUID termId,
            @RequestParam(required = false) UUID yearId) {
        return reportCards.student(studentId, termId, yearId);
    }

    // ------------------------------------------------------------------ analysis

    @GetMapping("/{examId}/classes/{classId}/analysis")
    @PreAuthorize(READ)
    public Analysis analysis(@PathVariable UUID examId, @PathVariable UUID classId) {
        return analysis.analysis(examId, classId);
    }

    /** The analysis as a CSV file for Excel (UTF-8 with a byte order mark). */
    @GetMapping("/{examId}/classes/{classId}/analysis.csv")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> analysisCsv(@PathVariable UUID examId, @PathVariable UUID classId) {
        AnalysisService.Download download = analysis.csv(examId, classId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(download.bytes());
    }
}
