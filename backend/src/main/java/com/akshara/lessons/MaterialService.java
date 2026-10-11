package com.akshara.lessons;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SectionView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.files.FileOwner;
import com.akshara.files.FileStore;
import com.akshara.files.IncomingFile;
import com.akshara.files.StoredFileInfo;
import com.akshara.lessons.LessonsForms.MaterialForm;
import com.akshara.lessons.LessonsViews.FileRef;
import com.akshara.lessons.LessonsViews.MaterialView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Study material: notes, files (the files module, up to five per piece, with the same limits and types as homework
 * attachments) and a link for a class, one section or every section, subject and optionally a topic. Students of the
 * class (and their parents) see it from its publish date. Teachers add material for the subjects they teach in the
 * class and change their own; approvers change any.
 */
@Service
@Transactional
public class MaterialService {

    static final String MODULE = "lessons";
    static final String MATERIAL_FILE = "material";
    static final int MAX_FILES = 5;

    /** The filters of the staff list; null means any. */
    public record MaterialFilter(UUID yearId, UUID classId, UUID sectionId, UUID subjectId, boolean mine) {
    }

    private final MaterialRepository materials;
    private final SyllabusRepository syllabi;
    private final SyllabusTopicRepository topics;
    private final FileStore files;
    private final LessonsSupport support;
    private final LessonsAccess access;
    private final AuditService audit;

    MaterialService(MaterialRepository materials, SyllabusRepository syllabi, SyllabusTopicRepository topics,
            FileStore files, LessonsSupport support, LessonsAccess access, AuditService audit) {
        this.materials = materials;
        this.syllabi = syllabi;
        this.topics = topics;
        this.files = files;
        this.support = support;
        this.access = access;
        this.audit = audit;
    }

    static FileOwner owner(UUID materialId) {
        return new FileOwner(MODULE, MATERIAL_FILE, materialId);
    }

    @Transactional(readOnly = true)
    public List<MaterialView> list(MaterialFilter filter, UUID me, LocalDate today) {
        TenantContext.require();
        Optional<YearInfo> year = support.yearOrCurrent(filter.yearId());
        if (year.isEmpty()) {
            return List.of();
        }
        LessonsAccess.Scope scope = access.scope(year.get().id());
        List<Material> found = materials.findByAcademicYearId(year.get().id()).stream()
                .filter(m -> filter.classId() == null || m.getClassId().equals(filter.classId()))
                .filter(m -> filter.sectionId() == null || m.getSectionId() == null
                        || m.getSectionId().equals(filter.sectionId()))
                .filter(m -> filter.subjectId() == null || m.getSubjectId().equals(filter.subjectId()))
                .filter(m -> !filter.mine() || (me != null && me.equals(m.getCreatedById())))
                .sorted(Comparator.comparing(Material::getPublishOn).reversed()
                        .thenComparing(Material::getCreatedAt, Comparator.reverseOrder()))
                .toList();
        return views(found, scope, today);
    }

    @Transactional(readOnly = true)
    public MaterialView detail(UUID id, LocalDate today) {
        TenantContext.require();
        Material m = material(id);
        return views(List.of(m), access.scope(m.getAcademicYearId()), today).getFirst();
    }

    Material material(UUID id) {
        return materials.findById(id).orElseThrow(() -> ApiException.notFound("Study material"));
    }

    List<MaterialView> views(List<Material> list, LessonsAccess.Scope scope, LocalDate today) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, ClassView> classes = support.classes();
        Map<UUID, SectionInfo> sections = support.sectionMap();
        Map<UUID, String> subjects = support.subjectNames();
        Map<UUID, String> topicTitles = topicTitles(list.stream().map(Material::getTopicId).toList());
        Map<UUID, List<StoredFileInfo>> attached = files.list(MODULE, MATERIAL_FILE,
                list.stream().map(Material::getId).toList());
        return list.stream().map(m -> {
            ClassView c = classes.get(m.getClassId());
            SectionInfo s = m.getSectionId() == null ? null : sections.get(m.getSectionId());
            return new MaterialView(m.getId(), m.getAcademicYearId(), m.getClassId(), c == null ? "" : c.name(),
                    m.getSectionId(), s == null ? null : s.label(), m.getSubjectId(),
                    subjects.getOrDefault(m.getSubjectId(), ""), m.getTopicId(),
                    m.getTopicId() == null ? null : topicTitles.get(m.getTopicId()), m.getTitle(), m.getNotes(),
                    m.getLinkUrl(), m.getPublishOn(), !m.getPublishOn().isAfter(today), m.getCreatedByName(),
                    m.getUpdatedAt(), fileRefs(attached.getOrDefault(m.getId(), List.of())),
                    scope.owns(m.getCreatedById()));
        }).toList();
    }

    Map<UUID, String> topicTitles(Collection<UUID> ids) {
        List<UUID> wanted = ids.stream().filter(id -> id != null).distinct().toList();
        Map<UUID, String> result = new HashMap<>();
        if (!wanted.isEmpty()) {
            topics.findAllById(wanted).forEach(t -> result.put(t.getId(), t.getTitle()));
        }
        return result;
    }

    static List<FileRef> fileRefs(List<StoredFileInfo> list) {
        return list.stream().map(f -> new FileRef(f.id(), f.name(), f.contentType(), f.size())).toList();
    }

    // ------------------------------------------------------------------ changes

    public MaterialView create(MaterialForm form, Actor actor, LocalDate today) {
        TenantContext.require();
        YearInfo year = support.yearInBody(form.academicYearId(), "academicYearId");
        Material.Content content = check(form, year, today);
        Material m = materials.saveAndFlush(new Material(year.id(), actor.id(), actor.name(), content));
        audit.record(actor, "study_material.created", "study_material", m.getId(), details(m));
        return detail(m.getId(), today);
    }

    public MaterialView update(UUID id, MaterialForm form, Actor actor, LocalDate today) {
        TenantContext.require();
        Material m = own(id);
        YearInfo year = support.year(m.getAcademicYearId());
        m.update(check(form, year, today));
        materials.saveAndFlush(m);
        audit.record(actor, "study_material.updated", "study_material", m.getId(), details(m));
        return detail(m.getId(), today);
    }

    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        Material m = own(id);
        int removed = files.deleteAll(owner(m.getId()));
        materials.delete(m);
        materials.flush();
        audit.record(actor, "study_material.deleted", "study_material", m.getId(), Map.of("title", m.getTitle(),
                "files", removed));
    }

    public MaterialView addFile(UUID id, IncomingFile file, Actor actor, LocalDate today) {
        TenantContext.require();
        Material m = own(id);
        if (files.list(owner(m.getId())).size() >= MAX_FILES) {
            throw ApiException.conflict("A piece of material can have at most " + MAX_FILES + " files.", "file");
        }
        StoredFileInfo stored = files.put(owner(m.getId()), file, actor.id());
        audit.record(actor, "study_material.file_added", "study_material", m.getId(), Map.of("title", m.getTitle(),
                "file", stored.name()));
        return detail(m.getId(), today);
    }

    public MaterialView removeFile(UUID id, UUID fileId, Actor actor, LocalDate today) {
        TenantContext.require();
        Material m = own(id);
        StoredFileInfo info = files.list(owner(m.getId())).stream().filter(f -> f.id().equals(fileId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("File"));
        files.delete(owner(m.getId()), fileId);
        audit.record(actor, "study_material.file_removed", "study_material", m.getId(), Map.of("title", m.getTitle(),
                "file", info.name()));
        return detail(m.getId(), today);
    }

    /** The material, if the caller may change it: its author, or an approver who also manages. 403 otherwise. */
    private Material own(UUID id) {
        Material m = material(id);
        if (!access.scope(m.getAcademicYearId()).owns(m.getCreatedById())) {
            throw LessonsAccess.notYours("Only the teacher who added this material can change it.", "material");
        }
        return m;
    }

    private Material.Content check(MaterialForm form, YearInfo year, LocalDate today) {
        ClassView c = support.classInBody(form.classId(), "classId");
        LessonsSupport.subjectOfClass(c, form.subjectId(), "subjectId");
        LessonsAccess.Scope scope = access.scope(year.id());
        if (form.sectionId() != null) {
            SectionInfo s = support.sectionInBody(form.sectionId(), "sectionId");
            if (!s.classId().equals(c.id())) {
                throw ApiException.badRequest("Pick a section of " + c.name() + ".", "sectionId");
            }
            if (!scope.teaches(s.id(), form.subjectId())) {
                throw LessonsAccess.notYours("You can add material only for the sections and subjects you teach.",
                        "sectionId");
            }
        } else if (!scope.teachesAny(c.sections().stream().map(SectionView::id).toList(), form.subjectId())) {
            throw LessonsAccess.notYours("You can add material only for the classes and subjects you teach.",
                    "classId");
        }
        if (form.topicId() != null) {
            SyllabusTopic t = topics.findById(form.topicId()).orElseThrow(() -> ApiException.badRequest(
                    "Pick a topic of the class's syllabus for the subject.", "topicId"));
            Syllabus s = syllabi.findById(t.getSyllabusId()).orElseThrow();
            if (!s.getClassId().equals(c.id()) || !s.getSubjectId().equals(form.subjectId())) {
                throw ApiException.badRequest("Pick a topic of the class's syllabus for the subject.", "topicId");
            }
        }
        LocalDate publishOn = form.publishOn() == null ? today : form.publishOn();
        if (publishOn.isBefore(year.startsOn()) || publishOn.isAfter(year.endsOn())) {
            throw ApiException.badRequest("Pick a publish date within " + year.name() + ".", "publishOn");
        }
        return new Material.Content(c.id(), form.sectionId(), form.subjectId(), form.topicId(),
                LessonsSupport.required(form.title(), 200, "title", "title", "Give the material a title."),
                LessonsSupport.text(form.notes(), 5000, "notes", "notes"),
                LessonsSupport.link(form.linkUrl(), "linkUrl"), publishOn);
    }

    private static Map<String, Object> details(Material m) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("title", m.getTitle());
        d.put("classId", m.getClassId());
        if (m.getSectionId() != null) {
            d.put("sectionId", m.getSectionId());
        }
        d.put("subjectId", m.getSubjectId());
        d.put("publishOn", m.getPublishOn().toString());
        return d;
    }
}
