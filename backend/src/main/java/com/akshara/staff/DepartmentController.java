package com.akshara.staff;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.staff.DepartmentService.DepartmentView;
import com.akshara.staff.StaffForms.DepartmentFields;

/** Departments and their heads. staff.read to list, staff.manage to change. */
@RestController
@RequestMapping("/api/staff/departments")
public class DepartmentController {

    private final DepartmentService departments;

    DepartmentController(DepartmentService departments) {
        this.departments = departments;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('staff.read', 'staff.manage', 'staff_attendance.manage')")
    public List<DepartmentView> list() {
        return departments.list();
    }

    @PostMapping
    @PreAuthorize(StaffController.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public DepartmentView create(@Valid @RequestBody DepartmentFields request) {
        return departments.create(request, StaffAuth.actor());
    }

    @PutMapping("/{id}")
    @PreAuthorize(StaffController.MANAGE)
    public DepartmentView update(@PathVariable UUID id, @Valid @RequestBody DepartmentFields request) {
        return departments.update(id, request, StaffAuth.actor());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(StaffController.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        departments.delete(id, StaffAuth.actor());
    }
}
