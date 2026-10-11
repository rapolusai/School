package com.akshara.lessons;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SubjectRef;
import com.akshara.academics.AcademicsService.SubjectView;
import com.akshara.shared.ApiException;

/**
 * Lookups the lessons services share: years, classes, sections and subjects by id (404 for another school's ids in a
 * path, 400 with a field error in a body), their names, and the cleaning of the text people type.
 */
@Component
class LessonsSupport {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;

    LessonsSupport(AcademicsDirectory academics, AcademicsService academicsService) {
        this.academics = academics;
        this.academicsService = academicsService;
    }

    /** Today in India. */
    static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    static LocalDate dateOf(Instant at) {
        return at.atZone(INDIA).toLocalDate();
    }

    // ------------------------------------------------------------------ years

    YearInfo year(UUID id) {
        return academics.year(id).orElseThrow(() -> ApiException.notFound("Academic year"));
    }

    /** The year asked for (404 when unknown), else the current one; empty when the school has neither. */
    Optional<YearInfo> yearOrCurrent(UUID id) {
        if (id != null) {
            return Optional.of(year(id));
        }
        return academics.currentYear().or(() -> academics.years().stream().findFirst());
    }

    /** The year named in a body (400 when unknown), else the current one (400 when there is none). */
    YearInfo yearInBody(UUID id, String field) {
        if (id != null) {
            return academics.year(id)
                    .orElseThrow(() -> ApiException.badRequest("Pick an academic year of this school.", field));
        }
        return academics.currentYear()
                .orElseThrow(() -> ApiException.badRequest("Set up the current academic year first.", field));
    }

    List<YearInfo> years() {
        return academics.years();
    }

    Optional<YearInfo> currentYear() {
        return academics.currentYear();
    }

    // ------------------------------------------------------------------ classes, sections, subjects

    /** Every class in display order, with sections and subjects. */
    Map<UUID, ClassView> classes() {
        Map<UUID, ClassView> result = new LinkedHashMap<>();
        academicsService.classes().forEach(c -> result.put(c.id(), c));
        return result;
    }

    ClassView classInBody(UUID id, String field) {
        ClassView c = id == null ? null : classes().get(id);
        if (c == null) {
            throw ApiException.badRequest("Pick a class of this school.", field);
        }
        return c;
    }

    /** The subject must be one the class is taught (school setup). */
    static SubjectRef subjectOfClass(ClassView c, UUID subjectId, String field) {
        return c.subjects().stream().filter(s -> s.id().equals(subjectId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Pick a subject taught in " + c.name() + ".", field));
    }

    SectionInfo section(UUID id) {
        return academics.section(id).orElseThrow(() -> ApiException.notFound("Section"));
    }

    SectionInfo sectionInBody(UUID id, String field) {
        if (id == null) {
            throw ApiException.badRequest("Pick a section.", field);
        }
        return academics.section(id).orElseThrow(() -> ApiException.badRequest("Pick a section of this school.",
                field));
    }

    Optional<SectionInfo> findSection(UUID id) {
        return academics.section(id);
    }

    List<SectionInfo> sections() {
        return academics.sections();
    }

    List<SectionInfo> sectionsOfClass(UUID classId) {
        return academics.sectionsOfClass(classId);
    }

    Map<UUID, SectionInfo> sectionMap() {
        return academics.sections().stream().collect(Collectors.toMap(SectionInfo::id, Function.identity()));
    }

    Map<UUID, String> subjectNames() {
        return academicsService.subjects().stream().collect(Collectors.toMap(SubjectView::id, SubjectView::name));
    }

    // ------------------------------------------------------------------ text

    /** One line of text: control characters and repeated spaces removed; null when blank. */
    static String line(String value, int max, String field, String label) {
        if (value == null) {
            return null;
        }
        String clean = value.replaceAll("\\p{Cntrl}+", " ").strip().replaceAll("\\s+", " ");
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.length() > max) {
            throw ApiException.badRequest("Keep the " + label + " to " + max + " characters.", field);
        }
        return clean;
    }

    /** A line that must be given. */
    static String required(String value, int max, String field, String label, String missing) {
        String clean = line(value, max, field, label);
        if (clean == null) {
            throw ApiException.badRequest(missing, field);
        }
        return clean;
    }

    /** Free text with line breaks kept; other control characters removed; null when blank. */
    static String text(String value, int max, String field, String label) {
        if (value == null) {
            return null;
        }
        String clean = value.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                .strip();
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.length() > max) {
            throw ApiException.badRequest("Keep the " + label + " to " + max + " characters.", field);
        }
        return clean;
    }

    /** A web link: http or https with a host; null when blank. */
    static String link(String value, String field) {
        String clean = line(value, 1000, field, "link");
        if (clean == null) {
            return null;
        }
        if (!isWebLink(clean)) {
            throw ApiException.badRequest("Enter a web link starting with https:// or http://.", field);
        }
        return clean;
    }

    /** Several web links, one per line; each must be http or https. */
    static String links(String value, String field) {
        String clean = text(value, 2000, field, "links");
        if (clean == null) {
            return null;
        }
        List<String> lines = clean.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        for (String l : lines) {
            if (!isWebLink(l)) {
                throw ApiException.badRequest("Put one web link per line, each starting with https:// or http://.",
                        field);
            }
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    static boolean isWebLink(String value) {
        if (value.contains(" ")) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
