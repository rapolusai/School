package com.akshara.students;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.students.StudentService.ChildView;

/** What parents and students see about themselves. */
@RestController
public class MyStudentsController {

    private final StudentService students;

    public MyStudentsController(StudentService students) {
        this.students = students;
    }

    /** The children linked to the signed-in parent's guardian record. */
    @GetMapping("/api/me/children")
    @PreAuthorize("hasAuthority('child.view')")
    public List<ChildView> children() {
        return students.childrenOf(CurrentUser.requireId());
    }

    /** The signed-in student's own record; 404 when their sign-in is not linked to a student. */
    @GetMapping("/api/me/student")
    @PreAuthorize("hasAuthority('dashboard.view')")
    public ChildView student() {
        return students.studentOf(CurrentUser.requireId()).orElseThrow(() -> ApiException.notFound("Student record"));
    }
}
