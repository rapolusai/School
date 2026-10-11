package com.akshara.chat;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/**
 * A school for the messages and PTM tests: Class 5 with sections A (class teacher Ravi Kumar) and B (class teacher
 * Meera Das, who also teaches Science in 5 A); Asha (5 A, mother Lata Rao), Chitra (5 A, mother Priya Nair) and Bala
 * (5 B, mother Kamala Devi), each mother with a sign-in; the Principal Lakshmi Iyer, an accountant, and Sunil, a
 * teacher with no class.
 */
abstract class ChatTestBase extends IntegrationTest {

    static final String LATA_PHONE = "9876501001";

    record World(School school, Session admin, Session principal, Session ravi, Session meera, Session sunil,
            Session accountant, Session lata, Session priya, Session kamala, String raviId, String meeraId,
            String sunilId, String lataId, String kamalaId, String sectionA, String sectionB, String science,
            String asha, String chitra, String bala) {
    }

    World world() throws Exception {
        School school = api.signup();
        Session admin = api.login(school);
        SchoolFixtures fixtures = new SchoolFixtures(api);
        fixtures.currentYear(admin);
        String classId = fixtures.schoolClass(admin, "Class 5");
        String maths = TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"Mathematics"}""").andExpect(status().isCreated()), "$.id");
        String science = TestApi.read(api.post("/api/academics/subjects", admin.accessToken(), """
                {"name":"Science"}""").andExpect(status().isCreated()), "$.id");
        api.put("/api/academics/classes/" + classId + "/subjects", admin.accessToken(), """
                {"subjectIds":["%s","%s"]}""".formatted(maths, science)).andExpect(status().isOk());
        String domain = "@" + school.code() + ".akshara.test";
        String raviId = staff(admin, "Ravi Kumar", "ravi" + domain, "T-1");
        String meeraId = staff(admin, "Meera Das", "meera" + domain, "T-2");
        String sunilId = api.createUser(admin, "Sunil Rao", "sunil" + domain, List.of("TEACHER"));
        api.createUser(admin, "Lakshmi Iyer", "principal" + domain, List.of("PRINCIPAL"));
        api.createUser(admin, "Meena Reddy", "accounts" + domain, List.of("ACCOUNTANT"));
        String sectionA = TestApi.read(api.post("/api/academics/classes/" + classId + "/sections",
                admin.accessToken(), """
                {"name":"A","capacity":40,"classTeacherId":"%s"}""".formatted(raviId))
                .andExpect(status().isCreated()), "$.id");
        String sectionB = TestApi.read(api.post("/api/academics/classes/" + classId + "/sections",
                admin.accessToken(), """
                {"name":"B","capacity":40,"classTeacherId":"%s"}""".formatted(meeraId))
                .andExpect(status().isCreated()), "$.id");
        api.post("/api/timetable/assignments", admin.accessToken(), """
                {"sectionId":"%s","subjectId":"%s","teacherId":"%s","periodsPerWeek":4}"""
                .formatted(sectionA, science, meeraId)).andExpect(status().isCreated());
        String asha = fixtures.student(admin, sectionA, "C-1", "Asha", "Rao", "Lata Rao", LATA_PHONE);
        String chitra = fixtures.student(admin, sectionA, "C-2", "Chitra", "Nair", "Priya Nair", "9876501004");
        String bala = fixtures.student(admin, sectionB, "C-3", "Bala", "Devi", "Kamala Devi", "9876501002");
        Session lata = parent(school, admin, asha, "lata" + domain);
        Session priya = parent(school, admin, chitra, "priya" + domain);
        Session kamala = parent(school, admin, bala, "kamala" + domain);
        String lataId = TestApi.read(api.get("/api/me", lata.accessToken()), "$.id");
        String kamalaId = TestApi.read(api.get("/api/me", kamala.accessToken()), "$.id");
        return new World(school, admin, api.login(school.code(), "principal" + domain, TestApi.PASSWORD),
                api.login(school.code(), "ravi" + domain, TestApi.PASSWORD),
                api.login(school.code(), "meera" + domain, TestApi.PASSWORD),
                api.login(school.code(), "sunil" + domain, TestApi.PASSWORD),
                api.login(school.code(), "accounts" + domain, TestApi.PASSWORD), lata, priya, kamala, raviId,
                meeraId, sunilId, lataId, kamalaId, sectionA, sectionB, science, asha, chitra, bala);
    }

    /** A teacher with a staff profile (so they can be recorded as leaving). Returns their user id. */
    String staff(Session admin, String name, String email, String code) throws Exception {
        return TestApi.read(api.post("/api/staff", admin.accessToken(), """
                {"name":"%s","email":"%s","password":"%s","roles":["TEACHER"],"employeeCode":"%s",
                 "designation":"Teacher","employmentType":"PERMANENT","dateOfJoining":"2020-06-01",
                 "mobile":"9876509999"}""".formatted(name, email, TestApi.PASSWORD, code))
                .andExpect(status().isCreated()), "$.userId");
    }

    /** The student's guardian gets a sign-in. */
    Session parent(School school, Session admin, String student, String email) throws Exception {
        String guardian = TestApi.read(api.get("/api/students/" + student, admin.accessToken()), "$.guardians[0].id");
        api.post("/api/students/" + student + "/guardians/" + guardian + "/sign-in", admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted(email, TestApi.PASSWORD))
                .andExpect(status().isOk());
        return api.login(school.code(), email, TestApi.PASSWORD);
    }

    /** Lata starts (or opens) her conversation with a teacher about Asha. Returns its id. */
    String lataTo(World w, String teacherId) throws Exception {
        return TestApi.read(api.post("/api/me/chat/conversations", w.lata().accessToken(), """
                {"studentId":"%s","teacherUserId":"%s"}""".formatted(w.asha(), teacherId)), "$.id");
    }

    /** Sends a message as a parent ({@code parent} true) or as staff, with optional text files. */
    ResultActions send(Session who, boolean parent, String conversation, String body, String... fileNames)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders.multipart(
                (parent ? "/api/me/chat/conversations/" : "/api/chat/conversations/") + conversation + "/messages");
        for (String name : fileNames) {
            request.file(new MockMultipartFile("files", name, "text/plain",
                    ("Contents of " + name).getBytes(StandardCharsets.UTF_8)));
        }
        if (body != null) {
            request.param("body", body);
        }
        request.header("Authorization", "Bearer " + who.accessToken());
        return mvc.perform(request);
    }

    static LocalDate today() {
        return ChatRules.today();
    }
}
