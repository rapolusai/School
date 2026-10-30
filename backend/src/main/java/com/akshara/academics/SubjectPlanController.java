package com.akshara.academics;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.academics.SubjectPlanService.ClassChoice;
import com.akshara.academics.SubjectPlanService.NewSubject;
import com.akshara.academics.SubjectPlanService.SaveResult;
import com.akshara.academics.SubjectPlanService.SubjectPlan;
import com.akshara.platform.Board;

/** Subjects by class: the board's usual subjects as suggestions, and saving every class's subjects at once. */
@RestController
@RequestMapping("/api/academics/subject-plan")
public class SubjectPlanController {

    private final SubjectPlanService plans;

    public SubjectPlanController(SubjectPlanService plans) {
        this.plans = plans;
    }

    public record NewSubjectRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9-]*$", message = "Use letters, digits or hyphens.")
            String code) {
    }

    public record ClassChoiceRequest(
            @NotNull UUID classId,
            @NotNull @Size(max = 200) List<@NotNull UUID> subjectIds,
            @Size(max = 200) List<@NotBlank @Size(max = 100) String> newSubjects) {
    }

    public record PlanRequest(
            @Size(max = 200) List<@Valid @NotNull NewSubjectRequest> newSubjects,
            @NotNull @Size(max = 300) List<@Valid @NotNull ClassChoiceRequest> classes) {
    }

    @GetMapping
    @PreAuthorize(AcademicsController.READ)
    public SubjectPlan plan(@RequestParam(required = false) Board board) {
        return plans.plan(board);
    }

    @PutMapping
    @PreAuthorize(AcademicsController.MANAGE)
    public SaveResult save(@Valid @RequestBody PlanRequest request) {
        List<NewSubject> newSubjects = request.newSubjects() == null ? List.of()
                : request.newSubjects().stream().map(s -> new NewSubject(s.name(), s.code())).toList();
        List<ClassChoice> choices = request.classes().stream()
                .map(c -> new ClassChoice(c.classId(), c.subjectIds(),
                        c.newSubjects() == null ? List.of() : c.newSubjects()))
                .toList();
        return plans.save(newSubjects, choices);
    }
}
