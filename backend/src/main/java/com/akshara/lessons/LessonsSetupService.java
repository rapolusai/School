package com.akshara.lessons;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.lessons.LessonsForms.SettingsForm;
import com.akshara.lessons.LessonsViews.ClassOption;
import com.akshara.lessons.LessonsViews.Options;
import com.akshara.lessons.LessonsViews.SectionRef;
import com.akshara.lessons.LessonsViews.Settings;
import com.akshara.lessons.LessonsViews.SubjectRef;
import com.akshara.lessons.LessonsViews.TeachingRef;
import com.akshara.lessons.LessonsViews.YearRef;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;

/** The pickers of the lessons screens, and the school's lessons settings (online test messages, off by default). */
@Service
@Transactional
public class LessonsSetupService {

    private final LessonsSettingsRepository settings;
    private final LessonsSupport support;
    private final LessonsAccess access;
    private final AuditService audit;

    LessonsSetupService(LessonsSettingsRepository settings, LessonsSupport support, LessonsAccess access,
            AuditService audit) {
        this.settings = settings;
        this.support = support;
        this.access = access;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Options options(UUID yearId) {
        TenantContext.require();
        Optional<YearInfo> year = support.yearOrCurrent(yearId);
        List<YearRef> years = support.years().stream()
                .map(y -> new YearRef(y.id(), y.name(), y.startsOn(), y.endsOn(), y.current())).toList();
        List<ClassOption> classes = support.classes().values().stream()
                .map(c -> new ClassOption(c.id(), c.name(),
                        c.sections().stream()
                                .sorted(Comparator.comparing(s -> s.name().toLowerCase()))
                                .map(s -> new SectionRef(s.id(), s.name(), c.name() + " " + s.name())).toList(),
                        c.subjects().stream().map(s -> new SubjectRef(s.id(), s.name())).toList()))
                .toList();
        LessonsAccess.Scope scope = access.scope(year.map(YearInfo::id).orElse(null));
        List<TeachingRef> teaching = scope.teaching().stream()
                .map(t -> new TeachingRef(t.sectionId(), t.subjectId())).toList();
        return new Options(year.map(YearInfo::id).orElse(null), years, classes, scope.userId(), scope.wholeSchool(),
                LessonsAccess.can(Permissions.LESSONS_MANAGE), LessonsAccess.can(Permissions.LESSONS_APPROVE),
                teaching);
    }

    @Transactional(readOnly = true)
    public Settings settings() {
        TenantContext.require();
        return new Settings(notificationsEnabled());
    }

    boolean notificationsEnabled() {
        return settings.findFirstBy().map(LessonsSettings::isTestNotificationsEnabled).orElse(false);
    }

    public Settings updateSettings(SettingsForm form, Actor actor) {
        TenantContext.require();
        LessonsSettings row = settings.findFirstBy().orElseGet(() -> new LessonsSettings(false));
        boolean before = row.isTestNotificationsEnabled();
        row.update(form.testNotificationsEnabled());
        settings.saveAndFlush(row);
        audit.record(actor, "lessons_settings.updated", "lessons_settings", row.getId(),
                Map.of("testNotificationsEnabled", form.testNotificationsEnabled(), "before", before));
        return new Settings(row.isTestNotificationsEnabled());
    }
}
