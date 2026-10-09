package com.akshara.homework;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface HomeworkSectionRepository extends JpaRepository<HomeworkSection, UUID> {

    List<HomeworkSection> findByHomeworkId(UUID homeworkId);

    List<HomeworkSection> findByHomeworkIdIn(Collection<UUID> homeworkIds);
}
