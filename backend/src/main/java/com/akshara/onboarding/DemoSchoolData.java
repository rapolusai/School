package com.akshara.onboarding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.students.Gender;
import com.akshara.students.GuardianRelation;
import com.akshara.students.StudentForms;
import com.akshara.students.StudentForms.GuardianFields;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentDetail;

/**
 * The demo school's setup and students: two academic years, LKG to Class 10, common subjects and about sixty students
 * who were admitted in 2025-26 and promoted into 2026-27 (plus new admissions), so class lists, enrollment history,
 * siblings and alumni all have something to show. Names are invented; phone numbers come from the unused 98765 0xxxx
 * range. Runs inside one transaction as the demo school.
 */
final class DemoSchoolData {

    static final String CLASS_TEACHER_SECTION = "Class 5|A";

    /** first|last|gender|class|section|year admitted|guardian|relation. Sharma children share both parents. */
    static final List<String> STUDENTS = List.of(
            "Aarav|Reddy|M|LKG|A|2026|Srinivas Reddy|FATHER",
            "Myra|Iyer|F|LKG|A|2026|Kavya Iyer|MOTHER",
            "Vihaan|Naidu|M|LKG|A|2026|Prakash Naidu|FATHER",
            "Anika|Joshi|F|LKG|A|2026|Pooja Joshi|MOTHER",
            "Reyansh|Gupta|M|LKG|A|2025|Amit Gupta|FATHER",
            "Saanvi|Rao|F|LKG|A|2025|Lakshmi Rao|MOTHER",
            "Kabir|Khan|M|LKG|A|2025|Imran Khan|FATHER",
            "Ishaan|Verma|M|UKG|A|2025|Rohit Verma|FATHER",
            "Aadhya|Pillai|F|UKG|A|2025|Deepa Pillai|MOTHER",
            "Vivaan|Menon|M|UKG|A|2025|Sunil Menon|FATHER",
            "Zara|Shaikh|F|UKG|A|2025|Ayesha Shaikh|MOTHER",
            "Advik|Kulkarni|M|Class 1|B|2026|Mahesh Kulkarni|FATHER",
            "Kiara|Desai|F|Class 1|B|2026|Neha Desai|MOTHER",
            "Arnav|Das|M|Class 1|B|2026|Subhash Das|FATHER",
            "Diya|Sharma|F|Class 1|A|2025|Anitha Sharma|MOTHER",
            "Atharv|Patil|M|Class 1|A|2025|Ganesh Patil|FATHER",
            "Navya|Krishnan|F|Class 1|A|2025|Revathi Krishnan|MOTHER",
            "Dhruv|Chatterjee|M|Class 1|A|2025|Arindam Chatterjee|FATHER",
            "Ira|Banerjee|F|Class 1|B|2025|Sudeshna Banerjee|MOTHER",
            "Rudra|Yadav|M|Class 1|B|2025|Ramesh Yadav|FATHER",
            "Pari|Agarwal|F|Class 1|B|2025|Sunita Agarwal|MOTHER",
            "Aryan|Mishra|M|Class 2|A|2025|Alok Mishra|FATHER",
            "Anvi|Hegde|F|Class 2|A|2025|Shruti Hegde|MOTHER",
            "Krishna|Murthy|M|Class 2|A|2025|Venkatesh Murthy|FATHER",
            "Tara|Bose|F|Class 2|A|2025|Mitali Bose|MOTHER",
            "Shaurya|Singh|M|Class 2|B|2025|Vikram Singh|FATHER",
            "Meera|Nair|F|Class 2|B|2025|Sreeja Nair|MOTHER",
            "Yash|Jain|M|Class 2|B|2025|Manoj Jain|FATHER",
            "Riya|Kapoor|F|Class 3|A|2025|Sonia Kapoor|MOTHER",
            "Aditya|Bhat|M|Class 3|A|2025|Raghavendra Bhat|FATHER",
            "Sara|Thomas|F|Class 3|A|2025|Mary Thomas|MOTHER",
            "Om|Prakash|M|Class 3|A|2025|Jai Prakash|FATHER",
            "Avni|Saxena|F|Class 3|B|2025|Ritu Saxena|MOTHER",
            "Harsh|Vardhan|M|Class 3|B|2025|Anil Vardhan|FATHER",
            "Inaya|Mirza|F|Class 3|B|2025|Farah Mirza|MOTHER",
            "Arjun|Sharma|M|Class 4|A|2025|Anitha Sharma|MOTHER",
            "Ananya|Patel|F|Class 4|A|2025|Priya Patel|MOTHER",
            "Rohan|Das|M|Class 4|A|2025|Bijoy Das|FATHER",
            "Fatima|Siddiqui|F|Class 4|A|2025|Arif Siddiqui|FATHER",
            "Neel|Kapoor|M|Class 4|B|2025|Rajiv Kapoor|FATHER",
            "Laila|Ahmed|F|Class 4|B|2025|Nasreen Ahmed|MOTHER",
            "Ved|Kulkarni|M|Class 4|B|2025|Sanjay Kulkarni|FATHER",
            "Pranav|Reddy|M|Class 5|A|2025|Venkat Reddy|FATHER",
            "Sneha|Iyengar|F|Class 5|A|2025|Padma Iyengar|MOTHER",
            "Kunal|Mehta|M|Class 5|B|2025|Hitesh Mehta|FATHER",
            "Divya|Rao|F|Class 5|B|2025|Sharada Rao|MOTHER",
            "Aman|Tiwari|M|Class 6|A|2025|Rajesh Tiwari|FATHER",
            "Pooja|Shetty|F|Class 6|A|2025|Asha Shetty|MOTHER",
            "Siddharth|Menon|M|Class 6|A|2025|Gopal Menon|FATHER",
            "Nikhil|Varma|M|Class 7|A|2025|Suresh Varma|FATHER",
            "Shreya|Ghosh|F|Class 7|A|2025|Ruma Ghosh|MOTHER",
            "Mohammed|Ali|M|Class 7|A|2025|Yusuf Ali|FATHER",
            "Kavya|Srinivasan|F|Class 8|A|2025|Uma Srinivasan|MOTHER",
            "Rahul|Pandey|M|Class 8|A|2025|Dinesh Pandey|FATHER",
            "Simran|Kaur|F|Class 8|A|2025|Harpreet Kaur|MOTHER",
            "Varun|Chopra|M|Class 9|A|2025|Ashok Chopra|FATHER",
            "Nandini|Reddy|F|Class 9|A|2025|Sujatha Reddy|MOTHER",
            "Abhishek|Goud|M|Class 9|A|2025|Narsimha Goud|FATHER",
            "Tanvi|Deshpande|F|Class 10|A|2025|Madhuri Deshpande|MOTHER",
            "Karthik|Raman|M|Class 10|A|2025|Raman Subramanian|FATHER",
            "Ayaan|Qureshi|M|Class 10|A|2025|Salim Qureshi|FATHER");

    static final List<String> CLASSES = List.of("LKG", "UKG", "Class 1", "Class 2", "Class 3", "Class 4", "Class 5",
            "Class 6", "Class 7", "Class 8", "Class 9", "Class 10");

    /** name|code */
    static final List<String> SUBJECTS = List.of("English|ENG", "Hindi|HIN", "Telugu|TEL", "Mathematics|MAT",
            "Environmental Studies|EVS", "Science|SCI", "Social Studies|SST", "Computer Science|CS",
            "General Knowledge|GK", "Art and Craft|ART", "Physical Education|PE", "Music|MUS");

    static final List<String> PRE_PRIMARY_SUBJECTS = List.of("English", "Hindi", "Mathematics",
            "Environmental Studies", "Art and Craft", "Music", "Physical Education");
    static final List<String> PRIMARY_SUBJECTS = List.of("English", "Hindi", "Telugu", "Mathematics",
            "Environmental Studies", "Computer Science", "General Knowledge", "Art and Craft", "Physical Education");
    static final List<String> MIDDLE_SUBJECTS = List.of("English", "Hindi", "Telugu", "Mathematics", "Science",
            "Social Studies", "Computer Science", "Physical Education");

    static final String MOTHER_OF_ARJUN = "Anitha Sharma";
    static final String FATHER_OF_ARJUN = "Rakesh Sharma";

    private final AcademicsService academics;
    private final StudentService students;
    private final Actor actor;

    DemoSchoolData(AcademicsService academics, StudentService students, Actor actor) {
        this.academics = academics;
        this.students = students;
        this.actor = actor;
    }

    /** Returns the number of students created. */
    int seed(UUID teacherId, String parentEmail, String studentEmail) {
        Map<String, UUID> subjectIds = new HashMap<>();
        for (String entry : SUBJECTS) {
            String[] parts = entry.split("\\|");
            subjectIds.put(parts[0], academics.createSubject(parts[0], parts[1], actor).id());
        }

        Map<String, UUID> sections = new LinkedHashMap<>();
        for (int i = 0; i < CLASSES.size(); i++) {
            String name = CLASSES.get(i);
            UUID classId = academics.createClass(name, i + 1, actor).id();
            int level = level(name);
            List<String> letters = level >= 1 && level <= 5 ? List.of("A", "B") : List.of("A");
            for (String letter : letters) {
                String key = name + "|" + letter;
                UUID teacher = CLASS_TEACHER_SECTION.equals(key) ? teacherId : null;
                sections.put(key, academics.createSection(classId, letter, 40, teacher, actor).id());
            }
            List<String> subjects = level <= 0 ? PRE_PRIMARY_SUBJECTS : level <= 5 ? PRIMARY_SUBJECTS : MIDDLE_SUBJECTS;
            academics.setClassSubjects(classId, subjects.stream().map(subjectIds::get).toList(), actor);
        }

        YearInfo previous = academics.createYear("2025-26", LocalDate.of(2025, 6, 1), LocalDate.of(2026, 3, 31),
                true, actor);
        Map<String, Integer> rollNos = new HashMap<>();
        int count = 0;
        UUID arjunId = null;
        UUID dhiyaId = null;
        for (int i = 0; i < STUDENTS.size(); i++) {
            if (STUDENTS.get(i).contains("|2025|")) {
                UUID id = admit(i, sections, rollNos);
                count++;
                if (STUDENTS.get(i).startsWith("Arjun|Sharma")) {
                    arjunId = id;
                }
                if (STUDENTS.get(i).startsWith("Diya|Sharma")) {
                    dhiyaId = id;
                }
            }
        }

        YearInfo current = academics.createYear("2026-27", LocalDate.of(2026, 6, 1), LocalDate.of(2027, 3, 31),
                false, actor);
        for (Map.Entry<String, UUID> source : sections.entrySet()) {
            String[] key = source.getKey().split("\\|");
            int level = level(key[0]);
            if (level == 10) {
                students.promote(new StudentForms.Promote(source.getValue(), previous.id(), null, null, true), actor);
                continue;
            }
            String nextClass = CLASSES.get(CLASSES.indexOf(key[0]) + 1);
            String target = sections.containsKey(nextClass + "|" + key[1]) ? nextClass + "|" + key[1] : nextClass + "|A";
            students.promote(new StudentForms.Promote(source.getValue(), previous.id(), sections.get(target),
                    current.id(), false), actor);
        }
        academics.setCurrentYear(current.id(), actor);

        rollNos.clear();
        for (int i = 0; i < STUDENTS.size(); i++) {
            if (STUDENTS.get(i).contains("|2026|")) {
                admit(i, sections, rollNos);
                count++;
            }
        }

        if (arjunId != null && dhiyaId != null) {
            StudentDetail arjun = students.detail(arjunId);
            UUID motherId = arjun.guardians().stream().filter(g -> g.name().equals(MOTHER_OF_ARJUN)).findFirst()
                    .orElseThrow().id();
            students.linkGuardianSignIn(arjunId, motherId, parentEmail, null, actor);
            students.linkStudentSignIn(arjunId, studentEmail, null, actor);
        }
        return count;
    }

    private UUID admit(int index, Map<String, UUID> sections, Map<String, Integer> rollNos) {
        String[] p = STUDENTS.get(index).split("\\|");
        String sectionKey = p[3] + "|" + p[4];
        int admittedIn = Integer.parseInt(p[5]);
        int birthYear = admittedIn - 5 - level(p[3]);
        LocalDate dateOfBirth = LocalDate.of(birthYear, 1 + (index * 5) % 12, 1 + (index * 7) % 28);
        LocalDate admissionDate = admittedIn == 2025 ? LocalDate.of(2025, 6, 2) : LocalDate.of(2026, 6, 1);
        int roll = rollNos.merge(sectionKey, 1, Integer::sum);
        String admissionNo = "AKS/" + admittedIn + "/" + String.format("%03d", index + 1);

        List<GuardianFields> guardians = new ArrayList<>();
        if (p[6].equals(MOTHER_OF_ARJUN)) {
            guardians.add(new GuardianFields(MOTHER_OF_ARJUN, GuardianRelation.MOTHER, "9876500001",
                    null, "Architect", true));
            guardians.add(new GuardianFields(FATHER_OF_ARJUN, GuardianRelation.FATHER, "9876500002",
                    null, "Bank officer", false));
        } else {
            guardians.add(new GuardianFields(p[6], GuardianRelation.valueOf(p[7]),
                    "98765" + String.format("%05d", 1001 + index), null, null, true));
        }
        boolean arjun = p[0].equals("Arjun") && p[1].equals("Sharma");
        StudentForms.CreateStudent form = new StudentForms.CreateStudent(admissionNo, p[0], p[1], dateOfBirth,
                p[2].equals("M") ? Gender.MALE : Gender.FEMALE, admissionDate, arjun ? "B+" : null,
                arjun ? "Plot 12, Road 3, Banjara Hills, Hyderabad 500034" : null, null, null,
                sections.get(sectionKey), roll, guardians);
        return students.create(form, actor).id();
    }

    /** LKG is -1, UKG 0, "Class 5" 5. */
    static int level(String className) {
        return switch (className) {
            case "LKG" -> -1;
            case "UKG" -> 0;
            default -> Integer.parseInt(className.substring("Class ".length()));
        };
    }
}
