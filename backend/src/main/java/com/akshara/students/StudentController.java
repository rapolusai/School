package com.akshara.students;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.shared.ApiException;
import com.akshara.shared.ApiExceptionHandler;
import com.akshara.students.StudentForms.CreateStudent;
import com.akshara.students.StudentForms.GuardianFields;
import com.akshara.students.StudentForms.Leave;
import com.akshara.students.StudentForms.Promote;
import com.akshara.students.StudentForms.SignIn;
import com.akshara.students.StudentForms.SignInMode;
import com.akshara.students.StudentForms.UpdateStudent;
import com.akshara.students.StudentImportService.ImportResult;
import com.akshara.students.StudentService.GuardianView;
import com.akshara.students.StudentService.PromotionResult;
import com.akshara.students.StudentService.StudentDetail;
import com.akshara.students.StudentService.StudentPage;
import com.akshara.students.StudentService.StudentQuery;

/** Student records. Reading needs students.read; every change needs students.manage. */
@RestController
@RequestMapping("/api/students")
public class StudentController {

    static final String READ = "hasAuthority('students.read')";
    static final String MANAGE = "hasAuthority('students.manage')";

    private final StudentService students;
    private final StudentImportService importer;
    private final PasswordEncoder passwordEncoder;

    public StudentController(StudentService students, StudentImportService importer,
            PasswordEncoder passwordEncoder) {
        this.students = students;
        this.importer = importer;
        this.passwordEncoder = passwordEncoder;
    }

    /** CSV text of up to 2,000 students (about 2 MB). */
    public record ImportRequest(@NotNull @Size(max = 2_000_000, message = "The file is too large.") String csv) {
    }

    @GetMapping
    @PreAuthorize(READ)
    public StudentPage list(
            @RequestParam(required = false) UUID yearId,
            @RequestParam(required = false) UUID classId,
            @RequestParam(required = false) UUID sectionId,
            @RequestParam(required = false) StudentStatus status,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(StudentService.MAX_PAGE_SIZE) int size) {
        return students.list(new StudentQuery(yearId, classId, sectionId, status, q, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public StudentDetail get(@PathVariable UUID id) {
        return students.detail(id);
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public StudentDetail create(@Valid @RequestBody CreateStudent request) {
        return students.create(request, null);
    }

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE)
    public StudentDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateStudent request) {
        return students.update(id, request);
    }

    @PostMapping("/{id}/leave")
    @PreAuthorize(MANAGE)
    public StudentDetail leave(@PathVariable UUID id, @Valid @RequestBody Leave request) {
        return students.leave(id, request);
    }

    @PostMapping("/{id}/guardians")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public GuardianView addGuardian(@PathVariable UUID id, @Valid @RequestBody GuardianFields request) {
        return students.addGuardian(id, request);
    }

    @PutMapping("/{id}/guardians/{guardianId}")
    @PreAuthorize(MANAGE)
    public GuardianView updateGuardian(@PathVariable UUID id, @PathVariable UUID guardianId,
            @Valid @RequestBody GuardianFields request) {
        return students.updateGuardian(id, guardianId, request);
    }

    @DeleteMapping("/{id}/guardians/{guardianId}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeGuardian(@PathVariable UUID id, @PathVariable UUID guardianId) {
        students.removeGuardian(id, guardianId);
    }

    @PostMapping("/{id}/guardians/{guardianId}/sign-in")
    @PreAuthorize(MANAGE)
    public GuardianView guardianSignIn(@PathVariable UUID id, @PathVariable UUID guardianId,
            @Valid @RequestBody SignIn request) {
        return students.linkGuardianSignIn(id, guardianId, request.email().trim(), hashFor(request), null);
    }

    @PostMapping("/{id}/sign-in")
    @PreAuthorize(MANAGE)
    public StudentDetail studentSignIn(@PathVariable UUID id, @Valid @RequestBody SignIn request) {
        return students.linkStudentSignIn(id, request.email().trim(), hashFor(request), null);
    }

    @PostMapping("/promote")
    @PreAuthorize(MANAGE)
    public PromotionResult promote(@Valid @RequestBody Promote request) {
        return students.promote(request, null);
    }

    /**
     * Checks (dryRun=true, the default) or imports CSV rows. A real import with any bad row writes nothing and
     * answers 400 with the row problems.
     */
    @PostMapping("/import")
    @PreAuthorize(MANAGE)
    public ResponseEntity<?> importCsv(@RequestParam(defaultValue = "true") boolean dryRun,
            @Valid @RequestBody ImportRequest request) {
        ImportResult result = importer.run(request.csv(), dryRun, null);
        if (!dryRun && !result.committed()) {
            ResponseEntity<ProblemDetail> problem = ApiExceptionHandler.problem(HttpStatus.BAD_REQUEST,
                    "Check the file", result.invalidRows() + (result.invalidRows() == 1 ? " row needs" : " rows need")
                            + " attention. Nothing was imported.", java.util.Map.of());
            ProblemDetail body = problem.getBody();
            body.setProperty("result", result);
            return problem;
        }
        return ResponseEntity.ok(result);
    }

    /** Hashes a new password before any transaction starts, so slow hashing never holds a connection. */
    private String hashFor(SignIn request) {
        if (request.mode() == SignInMode.LINK) {
            return null;
        }
        if (request.password() == null || request.password().length() < 10) {
            throw ApiException.badRequest("Use at least 10 characters.", "password");
        }
        return passwordEncoder.encode(request.password());
    }
}
