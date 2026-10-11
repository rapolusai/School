package com.akshara.lessons;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;

/**
 * Study material: notes, files and links for a class (every section or one), published on a date. Teachers add it
 * for what they teach and change only their own; students see their own class's published material and parents
 * their children's, files included; nobody else's.
 */
class StudyMaterialIT extends IntegrationTest {

    static final byte[] NOTES = "Place value: ones, tens, hundreds.\n".getBytes(StandardCharsets.UTF_8);

    private LessonsSchool s;

    @BeforeEach
    void school() throws Exception {
        s = new LessonsSchool(api);
    }

    @Test
    void studentsSeeTheirOwnClasssPublishedMaterialAndParentsTheirChilds() throws Exception {
        Session ravi = s.login("ravi");
        String topic = LessonsSchool.topic(s.mathsSyllabus(ravi), "Place value");
        // For 5 A only, with a topic, a link and a file.
        String chart = TestApi.read(api.post("/api/lessons/material", ravi.accessToken(), """
                {"classId":"%s","sectionId":"%s","subjectId":"%s","topicId":"%s","title":"Place value chart",
                 "notes":"Learn the chart.","linkUrl":"https://ncert.nic.in/textbook.php"}"""
                .formatted(s.class5, s.s5A, s.maths, topic)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.sectionLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.topicTitle").value("Place value"))
                .andExpect(jsonPath("$.publishOn").value(s.today.toString()))
                .andExpect(jsonPath("$.published").value(true))
                .andExpect(jsonPath("$.createdByName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.canEdit").value(true)), "$.id");
        String file = TestApi.read(attach(ravi, chart, new MockMultipartFile("file", "chart.txt", "text/plain",
                NOTES)).andExpect(status().isOk()).andExpect(jsonPath("$.files[0].name").value("chart.txt")),
                "$.files[0].id");
        // For every section of Class 5 (Ravi teaches Mathematics in one of them), published next week.
        String later = TestApi.read(api.post("/api/lessons/material", ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"Fractions with pictures","publishOn":"%s"}"""
                .formatted(s.class5, s.maths, s.today.plusDays(7))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.sectionId").doesNotExist()), "$.id");
        // Class 6 material, added by the School Admin.
        api.post("/api/lessons/material", s.admin.accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"Class 6 notes"}""".formatted(s.class6, s.maths))
                .andExpect(status().isCreated());

        // Asha (5 A) sees the chart with its file; not next week's, not Class 6's.
        Session asha = s.login("asha");
        api.get("/api/me/study", asha.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.classLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.material[*].title", contains("Place value chart")))
                .andExpect(jsonPath("$.material[0].files[0].name").value("chart.txt"))
                .andExpect(jsonPath("$.material[0].linkUrl").value("https://ncert.nic.in/textbook.php"))
                .andExpect(jsonPath("$.material[0].topicTitle").value("Place value"));
        api.get("/api/files/" + file, asha.accessToken()).andExpect(status().isOk())
                .andExpect(content().bytes(NOTES));
        // Dev (5 B) sees none of it, and cannot download the 5 A file.
        Session dev = s.login("dev");
        api.get("/api/me/study", dev.accessToken()).andExpect(jsonPath("$.material", hasSize(0)));
        api.get("/api/files/" + file, dev.accessToken()).andExpect(status().isNotFound());
        // Asha's mother sees her daughter's material, read-only.
        Session mother = s.login("mother");
        api.get("/api/me/children/" + s.asha + "/study", mother.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.material[*].title", contains("Place value chart")));
        api.get("/api/files/" + file, mother.accessToken()).andExpect(status().isOk());
        api.get("/api/me/children/" + s.dev + "/study", mother.accessToken()).andExpect(status().isNotFound());
        api.post("/api/lessons/material", mother.accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"Mine"}""".formatted(s.class5, s.maths))
                .andExpect(status().isForbidden());
        // A parent has no study page of their own; a student has no children.
        api.get("/api/me/study", mother.accessToken()).andExpect(status().isNotFound());
        api.get("/api/me/children/" + s.asha + "/study", asha.accessToken()).andExpect(status().isForbidden());

        // Next week the later material appears for both sections.
        api.put("/api/lessons/material/" + later, ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"Fractions with pictures","publishOn":"%s"}"""
                .formatted(s.class5, s.maths, s.today)).andExpect(status().isOk());
        api.get("/api/me/study", dev.accessToken())
                .andExpect(jsonPath("$.material[*].title", contains("Fractions with pictures")));
        api.get("/api/me/study", asha.accessToken())
                .andExpect(jsonPath("$.material[*].title", containsInAnyOrder("Place value chart",
                        "Fractions with pictures")));

        // Staff lists: by section (whole-class material included), and his own.
        api.get("/api/lessons/material?sectionId=" + s.s5B, s.login("principal").accessToken())
                .andExpect(jsonPath("$[*].title", contains("Fractions with pictures")));
        api.get("/api/lessons/material?mine=true", ravi.accessToken()).andExpect(jsonPath("$", hasSize(2)));
        api.get("/api/lessons/material?classId=" + s.class6, ravi.accessToken())
                .andExpect(jsonPath("$[0].canEdit").value(false));
        api.get("/api/lessons/material", asha.accessToken()).andExpect(status().isForbidden());

        api.get("/api/audit-events?limit=20", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("study_material.created", "study_material.file_added",
                        "study_material.updated")));
    }

    @Test
    void teachersAddMaterialForWhatTheyTeachAndChangeOnlyTheirOwn() throws Exception {
        Session ravi = s.login("ravi");
        Session sita = s.login("sita");
        api.post("/api/lessons/material", ravi.accessToken(), """
                {"classId":"%s","sectionId":"%s","subjectId":"%s","title":"Not mine"}"""
                .formatted(s.class5, s.s5B, s.maths))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errors.sectionId").exists());
        api.post("/api/lessons/material", ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"Not mine"}""".formatted(s.class6, s.maths))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errors.classId").exists());
        api.post("/api/lessons/material", ravi.accessToken(), """
                {"classId":"%s","sectionId":"%s","subjectId":"%s","title":"Wrong class"}"""
                .formatted(s.class5, s.s6A, s.maths))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.sectionId").exists());
        api.post("/api/lessons/material", ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"Link","linkUrl":"javascript:alert(1)"}"""
                .formatted(s.class5, s.maths))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.linkUrl").exists());
        api.post("/api/lessons/material", ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"  "}""".formatted(s.class5, s.maths))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.title").exists());
        api.post("/api/lessons/material", ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"Late","publishOn":"%s"}"""
                .formatted(s.class5, s.maths, s.today.plusYears(2)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.publishOn").exists());
        api.post("/api/lessons/material", s.login("principal").accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"Principal"}""".formatted(s.class5, s.maths))
                .andExpect(status().isForbidden());

        String id = TestApi.read(api.post("/api/lessons/material", ravi.accessToken(), """
                {"classId":"%s","sectionId":"%s","subjectId":"%s","title":"Mine"}"""
                .formatted(s.class5, s.s5A, s.maths)).andExpect(status().isCreated()), "$.id");
        api.put("/api/lessons/material/" + id, sita.accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"Taken"}""".formatted(s.class5, s.maths))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errors.material").exists());
        attach(sita, id, new MockMultipartFile("file", "x.txt", "text/plain", NOTES))
                .andExpect(status().isForbidden());
        api.delete("/api/lessons/material/" + id, sita.accessToken()).andExpect(status().isForbidden());

        // Files: the homework rules (type checked by content), at most five.
        attach(ravi, id, new MockMultipartFile("file", "tool.exe", "application/octet-stream",
                new byte[] {'M', 'Z', 0, 0, 1, 2, 3})).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file").exists());
        String file = null;
        for (int i = 1; i <= 5; i++) {
            file = TestApi.read(attach(ravi, id, new MockMultipartFile("file", "notes-" + i + ".txt", "text/plain",
                    NOTES)).andExpect(status().isOk()), "$.files[0].id");
        }
        attach(ravi, id, new MockMultipartFile("file", "notes-6.txt", "text/plain", NOTES))
                .andExpect(status().isConflict());
        api.delete("/api/lessons/material/" + id + "/files/" + file, ravi.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.files", hasSize(4)));
        api.delete("/api/lessons/material/" + id + "/files/" + file, ravi.accessToken())
                .andExpect(status().isNotFound());
        // Deleting the material deletes its files.
        String kept = TestApi.read(api.get("/api/lessons/material/" + id, ravi.accessToken()), "$.files[0].id");
        api.delete("/api/lessons/material/" + id, ravi.accessToken()).andExpect(status().isNoContent());
        api.get("/api/lessons/material/" + id, ravi.accessToken()).andExpect(status().isNotFound());
        api.get("/api/files/" + kept, s.admin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/audit-events?limit=30", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("study_material.deleted")))
                .andExpect(jsonPath("$[*].action", hasItem("study_material.file_removed")));
    }

    @Test
    void anotherSchoolsMaterialIsInvisible() throws Exception {
        Session ravi = s.login("ravi");
        String id = TestApi.read(api.post("/api/lessons/material", ravi.accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"Ours"}""".formatted(s.class5, s.maths))
                .andExpect(status().isCreated()), "$.id");
        String file = TestApi.read(attach(ravi, id, new MockMultipartFile("file", "ours.txt", "text/plain", NOTES))
                .andExpect(status().isOk()), "$.files[0].id");
        LessonsSchool other = new LessonsSchool(api);
        Session otherRavi = other.login("ravi");
        api.get("/api/lessons/material/" + id, otherRavi.accessToken()).andExpect(status().isNotFound());
        api.put("/api/lessons/material/" + id, otherRavi.accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"Theirs"}""".formatted(other.class5, other.maths))
                .andExpect(status().isNotFound());
        api.delete("/api/lessons/material/" + id, other.admin.accessToken()).andExpect(status().isNotFound());
        attach(otherRavi, id, new MockMultipartFile("file", "x.txt", "text/plain", NOTES))
                .andExpect(status().isNotFound());
        api.get("/api/files/" + file, other.admin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/files/" + file, other.login("asha").accessToken()).andExpect(status().isNotFound());
        api.get("/api/lessons/material", other.admin.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        api.get("/api/me/study", other.login("asha").accessToken()).andExpect(jsonPath("$.material", hasSize(0)));
        api.post("/api/lessons/material", otherRavi.accessToken(), """
                {"classId":"%s","subjectId":"%s","title":"Theirs"}""".formatted(s.class5, s.maths))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.classId").exists());
    }

    private ResultActions attach(Session session, String materialId, MockMultipartFile file) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.multipart("/api/lessons/material/" + materialId + "/files")
                .file(file).header("Authorization", "Bearer " + session.accessToken()));
    }
}
