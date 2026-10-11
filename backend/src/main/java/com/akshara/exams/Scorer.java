package com.akshara.exams;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsService.SubjectView;

/**
 * Works out students' scores in an exam from the marks on its sheets, with {@link GradeMath}. A student's marks are
 * found on whichever section's sheet they were entered, so a student who changed section keeps their marks.
 */
@Component
class Scorer {

    /** One student's scores in one exam, by subject id, and the totals. */
    record ExamScore(Map<UUID, GradeMath.SubjectScore> bySubject, GradeMath.Overall overall) {
    }

    /** The exam's papers (in date sheet order) and each student's scores. */
    record Scored(List<Paper> papers, Map<UUID, ExamScore> byStudent) {
    }

    private final PaperRepository papers;
    private final MarkSheetRepository sheets;
    private final MarkRepository marks;
    private final ExamSupport support;

    Scorer(PaperRepository papers, MarkSheetRepository sheets, MarkRepository marks, ExamSupport support) {
        this.papers = papers;
        this.sheets = sheets;
        this.marks = marks;
        this.support = support;
    }

    Scored score(ExamClass ec, Collection<UUID> studentIds, GradeMath.Scale scale,
            Map<UUID, SubjectView> subjects) {
        List<Paper> list = support.orderedPapers(papers.findByExamClassId(ec.getId()), subjects);
        Map<String, Mark> byKey = marksOf(list);
        Map<UUID, ExamScore> result = new LinkedHashMap<>();
        for (UUID studentId : studentIds) {
            Map<UUID, GradeMath.SubjectScore> bySubject = new LinkedHashMap<>();
            for (Paper p : list) {
                Mark m = byKey.get(studentId + ":" + p.getId());
                bySubject.put(p.getSubjectId(), GradeMath.subject(m == null || m.blank() ? null : m.entry(), p.max(),
                        scale));
            }
            result.put(studentId, new ExamScore(bySubject, GradeMath.overall(List.copyOf(bySubject.values()),
                    scale)));
        }
        return new Scored(list, result);
    }

    /** Every mark on the papers' sheets, by "studentId:paperId". */
    private Map<String, Mark> marksOf(List<Paper> list) {
        if (list.isEmpty()) {
            return Map.of();
        }
        Map<UUID, MarkSheet> sheetById = sheets.findByPaperIdIn(list.stream().map(Paper::getId).toList()).stream()
                .collect(Collectors.toMap(MarkSheet::getId, Function.identity()));
        if (sheetById.isEmpty()) {
            return Map.of();
        }
        Map<String, Mark> result = new HashMap<>();
        for (Mark m : marks.findBySheetIdIn(sheetById.keySet())) {
            result.put(m.getStudentId() + ":" + sheetById.get(m.getSheetId()).getPaperId(), m);
        }
        return result;
    }

    /** Students with marks on the section's sheets of the exam classes (they may have left the section since). */
    Set<UUID> studentsWithMarks(Collection<ExamClass> ecs, UUID sectionId) {
        if (ecs.isEmpty()) {
            return Set.of();
        }
        List<UUID> paperIds = papers.findByExamClassIdIn(ecs.stream().map(ExamClass::getId).toList()).stream()
                .map(Paper::getId).toList();
        if (paperIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> sheetIds = sheets.findByPaperIdIn(paperIds).stream()
                .filter(s -> s.getSectionId().equals(sectionId)).map(MarkSheet::getId).toList();
        if (sheetIds.isEmpty()) {
            return Set.of();
        }
        return marks.findBySheetIdIn(sheetIds).stream().filter(m -> !m.blank()).map(Mark::getStudentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
