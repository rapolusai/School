package com.akshara.academics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsService.SubjectRef;
import com.akshara.audit.AuditService;
import com.akshara.platform.Board;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Subjects for every class on one screen: what each class studies now, and the board's usual subjects for each class
 * level as suggestions. Saving creates any new subjects and sets the subjects of the classes sent, all in one
 * transaction, through the same rules (and audit entries) as setting one class's subjects.
 */
@Service
@Transactional
public class SubjectPlanService {

    /** A board's usual subject. {@code subjectId} is the school's subject of that name, when it has one. */
    public record StandardSubject(String name, String code, UUID subjectId) {
    }

    /**
     * A class with the subjects it studies now. {@code level} is the class level its name stands for (-2 nursery,
     * -1 LKG, 0 UKG, 1 to 12), or null. {@code suggested} and {@code optional} are names of standard subjects.
     */
    public record PlanClass(UUID id, String name, Integer level, List<UUID> subjectIds, List<String> suggested,
            List<String> optional) {
    }

    public record SubjectPlan(Board board, Board schoolBoard, List<Board> boards, List<SubjectRef> subjects,
            List<StandardSubject> standard, List<PlanClass> classes) {
    }

    /** A subject to create unless the school already has one of that name. */
    public record NewSubject(String name, String code) {
    }

    /** Exactly the subjects a class should study: existing ones by id and new ones by name. */
    public record ClassChoice(UUID classId, List<UUID> subjectIds, List<String> newSubjects) {
    }

    public record SaveResult(int subjectsCreated, int classesChanged) {
    }

    private final SchoolClassRepository classes;
    private final SubjectRepository subjects;
    private final ClassSubjectRepository classSubjects;
    private final AcademicsService academics;
    private final TenantDirectory tenants;
    private final AuditService audit;

    public SubjectPlanService(SchoolClassRepository classes, SubjectRepository subjects,
            ClassSubjectRepository classSubjects, AcademicsService academics, TenantDirectory tenants,
            AuditService audit) {
        this.classes = classes;
        this.subjects = subjects;
        this.classSubjects = classSubjects;
        this.academics = academics;
        this.tenants = tenants;
        this.audit = audit;
    }

    /** The plan with the suggestions of {@code board}, or of the school's own board when null. */
    @Transactional(readOnly = true)
    public SubjectPlan plan(Board board) {
        UUID tenantId = TenantContext.require();
        Board schoolBoard = tenants.profile(tenantId).map(p -> p.board()).orElse(Board.CBSE);
        Board shown = board != null ? board : schoolBoard;
        List<Subject> all = subjects.findAllOrdered();
        Map<String, UUID> idByName = new HashMap<>();
        all.forEach(s -> idByName.put(key(s.getName()), s.getId()));

        List<StandardSubject> standard = SubjectCatalog.entries(shown).stream()
                .map(e -> new StandardSubject(e.name(), e.code(), idByName.get(key(e.name()))))
                .toList();

        Map<UUID, List<UUID>> subjectsByClass = new HashMap<>();
        for (ClassSubject cs : classSubjects.findAll()) {
            subjectsByClass.computeIfAbsent(cs.getClassId(), k -> new ArrayList<>()).add(cs.getSubjectId());
        }
        List<PlanClass> planClasses = classes.findAllOrdered().stream().map(c -> {
            Integer level = SubjectCatalog.levelOf(c.getName());
            List<String> suggested = new ArrayList<>();
            List<String> optional = new ArrayList<>();
            if (level != null) {
                for (SubjectCatalog.Entry entry : SubjectCatalog.forLevel(shown, level)) {
                    (entry.optional() ? optional : suggested).add(entry.name());
                }
            }
            return new PlanClass(c.getId(), c.getName(), level, subjectsByClass.getOrDefault(c.getId(), List.of()),
                    suggested, optional);
        }).toList();

        return new SubjectPlan(shown, schoolBoard, List.of(Board.values()),
                all.stream().map(SubjectRef::of).toList(), standard, planClasses);
    }

    /**
     * Creates the new subjects (reusing one the school already has under the same name) and gives each class sent
     * exactly the subjects chosen. Classes not sent are left as they are. Nothing is written when anything is wrong.
     */
    public SaveResult save(Collection<NewSubject> newSubjects, Collection<ClassChoice> choices) {
        TenantContext.require();
        Map<UUID, SchoolClass> known = classes.findAll().stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        Set<UUID> seenClasses = new LinkedHashSet<>();
        for (ClassChoice choice : choices) {
            if (!known.containsKey(choice.classId())) {
                throw ApiException.badRequest("Pick classes from this school's list.", "classes");
            }
            if (!seenClasses.add(choice.classId())) {
                throw ApiException.badRequest(known.get(choice.classId()).getName() + " is listed twice.", "classes");
            }
        }

        Map<String, NewSubject> wantedNew = new LinkedHashMap<>();
        for (NewSubject subject : newSubjects) {
            String name = subject.name().trim();
            if (name.isEmpty()) {
                throw ApiException.badRequest("Give every new subject a name.", "newSubjects");
            }
            wantedNew.putIfAbsent(key(name), new NewSubject(name, AcademicsService.blankToNull(subject.code())));
        }
        for (ClassChoice choice : choices) {
            for (String name : choice.newSubjects()) {
                if (!wantedNew.containsKey(key(name))) {
                    throw ApiException.badRequest("List " + name.trim() + " under the new subjects too.", "classes");
                }
            }
        }

        Map<String, UUID> idByName = new HashMap<>();
        subjects.findAll().forEach(s -> idByName.put(key(s.getName()), s.getId()));
        int created = 0;
        for (Map.Entry<String, NewSubject> entry : wantedNew.entrySet()) {
            if (idByName.containsKey(entry.getKey())) {
                continue;
            }
            NewSubject subject = entry.getValue();
            // A code another subject already uses is dropped rather than refusing the whole plan.
            String code = subject.code() != null && subjects.existsByCodeExcept(subject.code(), new UUID(0, 0))
                    ? null : subject.code();
            idByName.put(entry.getKey(), academics.createSubject(subject.name(), code, null).id());
            created++;
        }

        int changed = 0;
        for (ClassChoice choice : choices) {
            Set<UUID> wanted = new LinkedHashSet<>(choice.subjectIds());
            choice.newSubjects().forEach(name -> wanted.add(idByName.get(key(name))));
            Set<UUID> before = classSubjects.findByClassId(choice.classId()).stream()
                    .map(ClassSubject::getSubjectId).collect(Collectors.toSet());
            if (!before.equals(wanted)) {
                academics.setClassSubjects(choice.classId(), wanted, null);
                changed++;
            }
        }
        if (created > 0 || changed > 0) {
            audit.record("subjects.plan_saved", "school", TenantContext.require(),
                    Map.of("subjectsCreated", created, "classesChanged", changed));
        }
        return new SaveResult(created, changed);
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
