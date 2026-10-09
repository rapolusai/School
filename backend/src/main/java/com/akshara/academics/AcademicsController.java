package com.akshara.academics;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SectionView;
import com.akshara.academics.AcademicsService.SubjectRef;
import com.akshara.academics.AcademicsService.SubjectView;
import com.akshara.academics.AcademicsService.TeacherRef;

/** School setup. Anyone with academics.read can look; changes need settings.manage. */
@RestController
@RequestMapping("/api/academics")
public class AcademicsController {

    static final String READ = "hasAuthority('academics.read')";
    static final String MANAGE = "hasAuthority('settings.manage')";

    private final AcademicsService academics;

    public AcademicsController(AcademicsService academics) {
        this.academics = academics;
    }

    public record YearRequest(
            @NotBlank @Size(max = 20) String name,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn,
            Boolean current) {
    }

    public record ClassRequest(
            @NotBlank @Size(max = 40) String name,
            @Min(0) @Max(999) Integer displayOrder) {
    }

    public record SectionRequest(
            @NotBlank @Size(max = 20) String name,
            @Min(value = 1, message = "Use a number from 1 to 500.") @Max(value = 500,
                    message = "Use a number from 1 to 500.") Integer capacity,
            UUID classTeacherId) {
    }

    public record SubjectRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9-]*$", message = "Use letters, digits or hyphens.")
            String code) {
    }

    public record ClassSubjectsRequest(@NotNull @Size(max = 200) List<@NotNull UUID> subjectIds) {
    }

    // ------------------------------------------------------------------ years

    @GetMapping("/years")
    @PreAuthorize(READ)
    public List<YearInfo> years() {
        return academics.years();
    }

    @PostMapping("/years")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public YearInfo createYear(@Valid @RequestBody YearRequest request) {
        return academics.createYear(request.name(), request.startsOn(), request.endsOn(),
                Boolean.TRUE.equals(request.current()), null);
    }

    @PutMapping("/years/{id}")
    @PreAuthorize(MANAGE)
    public YearInfo updateYear(@PathVariable UUID id, @Valid @RequestBody YearRequest request) {
        return academics.updateYear(id, request.name(), request.startsOn(), request.endsOn());
    }

    @PostMapping("/years/{id}/set-current")
    @PreAuthorize(MANAGE)
    public YearInfo setCurrent(@PathVariable UUID id) {
        return academics.setCurrentYear(id, null);
    }

    @DeleteMapping("/years/{id}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteYear(@PathVariable UUID id) {
        academics.deleteYear(id);
    }

    // ------------------------------------------------------------------ classes and sections

    @GetMapping("/classes")
    @PreAuthorize(READ)
    public List<ClassView> classes() {
        return academics.classes();
    }

    @GetMapping("/classes/{id}")
    @PreAuthorize(READ)
    public ClassView classView(@PathVariable UUID id) {
        return academics.classView(id);
    }

    @PostMapping("/classes")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ClassView createClass(@Valid @RequestBody ClassRequest request) {
        return academics.createClass(request.name(), request.displayOrder(), null);
    }

    @PutMapping("/classes/{id}")
    @PreAuthorize(MANAGE)
    public ClassView updateClass(@PathVariable UUID id, @Valid @RequestBody ClassRequest request) {
        return academics.updateClass(id, request.name(), request.displayOrder());
    }

    @DeleteMapping("/classes/{id}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteClass(@PathVariable UUID id) {
        academics.deleteClass(id);
    }

    @GetMapping("/classes/{id}/sections")
    @PreAuthorize(READ)
    public List<SectionView> sections(@PathVariable UUID id) {
        return academics.sections(id);
    }

    @PostMapping("/classes/{id}/sections")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public SectionView createSection(@PathVariable UUID id, @Valid @RequestBody SectionRequest request) {
        return academics.createSection(id, request.name(), request.capacity(), request.classTeacherId(), null);
    }

    @PutMapping("/sections/{id}")
    @PreAuthorize(MANAGE)
    public SectionView updateSection(@PathVariable UUID id, @Valid @RequestBody SectionRequest request) {
        return academics.updateSection(id, request.name(), request.capacity(), request.classTeacherId());
    }

    @DeleteMapping("/sections/{id}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSection(@PathVariable UUID id) {
        academics.deleteSection(id);
    }

    @GetMapping("/teachers")
    @PreAuthorize(READ)
    public List<TeacherRef> teachers() {
        return academics.teachers();
    }

    // ------------------------------------------------------------------ subjects

    @GetMapping("/subjects")
    @PreAuthorize(READ)
    public List<SubjectView> subjects() {
        return academics.subjects();
    }

    @PostMapping("/subjects")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public SubjectView createSubject(@Valid @RequestBody SubjectRequest request) {
        return academics.createSubject(request.name(), request.code(), null);
    }

    @PutMapping("/subjects/{id}")
    @PreAuthorize(MANAGE)
    public SubjectView updateSubject(@PathVariable UUID id, @Valid @RequestBody SubjectRequest request) {
        return academics.updateSubject(id, request.name(), request.code());
    }

    @DeleteMapping("/subjects/{id}")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSubject(@PathVariable UUID id) {
        academics.deleteSubject(id);
    }

    @GetMapping("/classes/{id}/subjects")
    @PreAuthorize(READ)
    public List<SubjectRef> classSubjects(@PathVariable UUID id) {
        return academics.classSubjects(id);
    }

    @PutMapping("/classes/{id}/subjects")
    @PreAuthorize(MANAGE)
    public List<SubjectRef> setClassSubjects(@PathVariable UUID id, @Valid @RequestBody ClassSubjectsRequest request) {
        return academics.setClassSubjects(id, request.subjectIds(), null);
    }
}
