package com.akshara.homework;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    List<Submission> findByHomeworkId(UUID homeworkId);

    List<Submission> findByHomeworkIdIn(Collection<UUID> homeworkIds);

    Optional<Submission> findByHomeworkIdAndStudentId(UUID homeworkId, UUID studentId);

    List<Submission> findByStudentIdAndHomeworkIdIn(UUID studentId, Collection<UUID> homeworkIds);

    long countByHomeworkId(UUID homeworkId);
}
