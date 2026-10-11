package com.akshara.exams;

import java.time.Instant;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.akshara.privacy.StudentDataErased;

/**
 * When a student's personal data is erased, clears the free text written about them: the class teacher's remarks
 * and the reflections of the holistic progress card. Marks and grades stay as academic records, tied to the student
 * record the privacy module has already anonymised. Runs inside the erasure's transaction.
 */
@Component
class ExamsErasure {

    private final TermRecordRepository records;
    private final HpcEntryRepository hpcEntries;

    ExamsErasure(TermRecordRepository records, HpcEntryRepository hpcEntries) {
        this.records = records;
        this.hpcEntries = hpcEntries;
    }

    @EventListener
    void on(StudentDataErased event) {
        Instant at = event.at() == null ? Instant.now() : event.at();
        records.findByStudentId(event.studentId()).forEach(r -> r.eraseRemark(at));
        hpcEntries.findByStudentId(event.studentId()).forEach(h -> h.eraseReflections(at));
    }
}
