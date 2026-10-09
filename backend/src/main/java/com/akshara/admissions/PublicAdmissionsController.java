package com.akshara.admissions;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.admissions.AdmissionForms.PublicEnquiry;
import com.akshara.admissions.AdmissionsService.PublicSchoolInfo;

/** A school's public enquiry form. No sign-in; rate limited per client IP and per school. */
@RestController
@RequestMapping("/api/public/schools/{code}")
public class PublicAdmissionsController {

    private final PublicEnquiryService enquiries;

    PublicAdmissionsController(PublicEnquiryService enquiries) {
        this.enquiries = enquiries;
    }

    @GetMapping("/admission-info")
    public PublicSchoolInfo info(@PathVariable String code, HttpServletRequest http) {
        return enquiries.info(code, http.getRemoteAddr());
    }

    /** Always answers {@code {"received": true}}: the enquiry's id and anything else about the school stay private. */
    @PostMapping("/enquiries")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Boolean> enquire(@PathVariable String code, @Valid @RequestBody PublicEnquiry request,
            HttpServletRequest http) {
        enquiries.submit(code, request, http.getRemoteAddr());
        return Map.of("received", true);
    }
}
