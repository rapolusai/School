package com.akshara.lessons;

/*
 * The lessons module's Spring Data repositories, one interface per entity. Kept in one file because each is a few
 * queries; all are package-private.
 */

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface LessonsSettingsRepository extends JpaRepository<LessonsSettings, UUID> {

    /** The school's settings: at most one row per school, and row-level security shows only this school's. */
    Optional<LessonsSettings> findFirstBy();
}

interface SyllabusRepository extends JpaRepository<Syllabus, UUID> {

    List<Syllabus> findByAcademicYearId(UUID academicYearId);

    Optional<Syllabus> findByAcademicYearIdAndClassIdAndSubjectId(UUID academicYearId, UUID classId, UUID subjectId);

    long countByAcademicYearId(UUID academicYearId);
}

interface SyllabusUnitRepository extends JpaRepository<SyllabusUnit, UUID> {

    List<SyllabusUnit> findBySyllabusIdOrderByPosition(UUID syllabusId);

    List<SyllabusUnit> findBySyllabusIdIn(Collection<UUID> syllabusIds);
}

interface SyllabusTopicRepository extends JpaRepository<SyllabusTopic, UUID> {

    List<SyllabusTopic> findBySyllabusIdOrderByPosition(UUID syllabusId);

    List<SyllabusTopic> findBySyllabusIdIn(Collection<UUID> syllabusIds);
}

interface LessonPlanRepository extends JpaRepository<LessonPlan, UUID> {

    List<LessonPlan> findByAcademicYearId(UUID academicYearId);

    List<LessonPlan> findByAcademicYearIdAndTeacherId(UUID academicYearId, UUID teacherId);

    List<LessonPlan> findByAcademicYearIdAndStatus(UUID academicYearId, PlanStatus status);

    @Query("select p from LessonPlan p where p.academicYearId = ?1 and p.status = com.akshara.lessons.PlanStatus.TAUGHT")
    List<LessonPlan> findTaught(UUID academicYearId);

    @Query("select count(p) from LessonPlan p where p.academicYearId = ?1 and p.teacherId = ?2 "
            + "and p.status in (com.akshara.lessons.PlanStatus.DRAFT, com.akshara.lessons.PlanStatus.RETURNED) "
            + "and p.weekStart <= ?3")
    long countToSubmit(UUID academicYearId, UUID teacherId, LocalDate weekStartAtMost);

    @Query("select count(p) from LessonPlan p where p.academicYearId = ?1 and p.teacherId <> ?2 "
            + "and p.status = com.akshara.lessons.PlanStatus.SUBMITTED")
    long countToApprove(UUID academicYearId, UUID approverId);

    long countByTopicIdIn(Collection<UUID> topicIds);

    /** How many plans each of the topics has: rows of (topic id, count). */
    @Query("select p.topicId, count(p) from LessonPlan p where p.topicId in ?1 group by p.topicId")
    List<Object[]> countPerTopic(Collection<UUID> topicIds);

    long countBySectionId(UUID sectionId);

    long countByAcademicYearId(UUID academicYearId);
}

interface MaterialRepository extends JpaRepository<Material, UUID> {

    List<Material> findByAcademicYearId(UUID academicYearId);

    List<Material> findByAcademicYearIdAndClassId(UUID academicYearId, UUID classId);

    long countBySectionId(UUID sectionId);

    long countByAcademicYearId(UUID academicYearId);
}

interface QuestionRepository extends JpaRepository<Question, UUID> {

    @Query("select q from Question q order by q.createdAt desc, q.id")
    List<Question> findAllNewestFirst();

    List<Question> findByClassIdAndSubjectId(UUID classId, UUID subjectId);
}

interface QuestionOptionRepository extends JpaRepository<QuestionOption, UUID> {

    List<QuestionOption> findByQuestionIdOrderByPosition(UUID questionId);

    List<QuestionOption> findByQuestionIdIn(Collection<UUID> questionIds);
}

interface OnlineTestRepository extends JpaRepository<OnlineTest, UUID> {

    List<OnlineTest> findByAcademicYearId(UUID academicYearId);

    long countByAcademicYearId(UUID academicYearId);

    /** The test, locked for this transaction: starting attempts and releasing results are serialised per test. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OnlineTest t where t.id = ?1")
    Optional<OnlineTest> lockById(UUID id);
}

interface TestSectionRepository extends JpaRepository<TestSection, UUID> {

    List<TestSection> findByTestId(UUID testId);

    List<TestSection> findByTestIdIn(Collection<UUID> testIds);

    List<TestSection> findBySectionId(UUID sectionId);

    long countBySectionId(UUID sectionId);
}

interface TestQuestionRepository extends JpaRepository<TestQuestion, UUID> {

    List<TestQuestion> findByTestIdOrderByPosition(UUID testId);

    List<TestQuestion> findByTestIdIn(Collection<UUID> testIds);

    List<TestQuestion> findByQuestionIdIn(Collection<UUID> questionIds);

    long countByQuestionId(UUID questionId);

    @Query("select count(tq) from TestQuestion tq join OnlineTest t on t.id = tq.testId where tq.questionId = ?1 "
            + "and t.status = com.akshara.lessons.TestStatus.PUBLISHED")
    long countInPublishedTests(UUID questionId);
}

interface TestAttemptRepository extends JpaRepository<TestAttempt, UUID> {

    List<TestAttempt> findByTestId(UUID testId);

    List<TestAttempt> findByTestIdIn(Collection<UUID> testIds);

    List<TestAttempt> findByTestIdAndStudentIdOrderByAttemptNo(UUID testId, UUID studentId);

    List<TestAttempt> findByStudentIdAndTestIdIn(UUID studentId, Collection<UUID> testIds);

    List<TestAttempt> findByStudentId(UUID studentId);

    long countByTestId(UUID testId);

    /** The attempt, locked for this transaction: saves and submission of one attempt are serialised. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from TestAttempt a where a.id = ?1")
    Optional<TestAttempt> lockById(UUID id);
}

interface TestAnswerRepository extends JpaRepository<TestAnswer, UUID> {

    List<TestAnswer> findByAttemptIdOrderByPosition(UUID attemptId);

    List<TestAnswer> findByAttemptIdIn(Collection<UUID> attemptIds);
}
