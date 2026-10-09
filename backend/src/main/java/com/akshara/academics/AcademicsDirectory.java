package com.akshara.academics;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.shared.TenantContext;

/** Read-only view of the current school's setup for other modules (students, and later attendance and exams). */
@Service
@Transactional(readOnly = true)
public class AcademicsDirectory {

    public record YearInfo(UUID id, String name, LocalDate startsOn, LocalDate endsOn, boolean current) {

        static YearInfo of(AcademicYear y) {
            return new YearInfo(y.getId(), y.getName(), y.getStartsOn(), y.getEndsOn(), y.isCurrent());
        }
    }

    /** A section with its class, ready for display as "Class 5 A". */
    public record SectionInfo(UUID id, String name, Integer capacity, UUID classId, String className, int classOrder,
            UUID classTeacherId, String classTeacherName) {

        public String label() {
            return className + " " + name;
        }
    }

    static final Comparator<SectionInfo> SECTION_ORDER = Comparator.comparingInt(SectionInfo::classOrder)
            .thenComparing(s -> s.className().toLowerCase())
            .thenComparing(s -> s.name().toLowerCase());

    private final AcademicYearRepository years;
    private final SchoolClassRepository classes;
    private final SectionRepository sections;
    private final UserRepository users;

    public AcademicsDirectory(AcademicYearRepository years, SchoolClassRepository classes, SectionRepository sections,
            UserRepository users) {
        this.years = years;
        this.classes = classes;
        this.sections = sections;
        this.users = users;
    }

    public Optional<YearInfo> currentYear() {
        TenantContext.require();
        return years.findByCurrentTrue().map(YearInfo::of);
    }

    public Optional<YearInfo> year(UUID id) {
        TenantContext.require();
        return years.findById(id).map(YearInfo::of);
    }

    /** Newest first. */
    public List<YearInfo> years() {
        TenantContext.require();
        return years.findAllByOrderByStartsOnDesc().stream().map(YearInfo::of).toList();
    }

    public Optional<SectionInfo> section(UUID id) {
        TenantContext.require();
        return sections.findById(id).map(s -> toInfo(List.of(s)).getFirst());
    }

    /** Every section of the school in class order. */
    public List<SectionInfo> sections() {
        TenantContext.require();
        return toInfo(sections.findAll());
    }

    public List<SectionInfo> sectionsOfClass(UUID classId) {
        TenantContext.require();
        return toInfo(sections.findByClassId(classId));
    }

    List<SectionInfo> toInfo(List<Section> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, SchoolClass> classById = classes.findAllById(list.stream().map(Section::getClassId).distinct().toList())
                .stream().collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        List<UUID> teacherIds = list.stream().map(Section::getClassTeacherId).filter(id -> id != null).distinct().toList();
        Map<UUID, String> teacherNames = teacherIds.isEmpty() ? Map.of()
                : users.findAllById(teacherIds).stream()
                        .collect(Collectors.toMap(UserAccount::getId, UserAccount::getName));
        return list.stream()
                .map(s -> {
                    SchoolClass c = classById.get(s.getClassId());
                    return new SectionInfo(s.getId(), s.getName(), s.getCapacity(), s.getClassId(), c.getName(),
                            c.getDisplayOrder(), s.getClassTeacherId(),
                            s.getClassTeacherId() == null ? null : teacherNames.get(s.getClassTeacherId()));
                })
                .sorted(SECTION_ORDER)
                .toList();
    }
}
