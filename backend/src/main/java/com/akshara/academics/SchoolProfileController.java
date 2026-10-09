package com.akshara.academics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.audit.AuditService;
import com.akshara.platform.SchoolProfile;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/** The school's own name, board and contact details. Every member may read it; admins keep it up to date. */
@RestController
@RequestMapping("/api/school/profile")
public class SchoolProfileController {

    private final TenantDirectory tenants;
    private final AuditService audit;

    public SchoolProfileController(TenantDirectory tenants, AuditService audit) {
        this.tenants = tenants;
        this.audit = audit;
    }

    public record ProfileRequest(
            @Size(max = 500) String address,
            @Size(max = 20) @Pattern(regexp = "^$|^\\+?[0-9][0-9 ()-]{6,18}[0-9]$",
                    message = "Enter a phone number with 8 to 15 digits.") String phone,
            @Email @Size(max = 254) String contactEmail,
            @Pattern(regexp = "^$|^[0-9]{11}$", message = "A UDISE code has 11 digits.") String udiseCode) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public SchoolProfile get() {
        UUID tenantId = TenantContext.current()
                .orElseThrow(() -> new AccessDeniedException("Only people in a school have a school profile"));
        return tenants.profile(tenantId).orElseThrow(() -> ApiException.notFound("School"));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('settings.manage')")
    @Transactional
    public SchoolProfile update(@Valid @RequestBody ProfileRequest request) {
        UUID tenantId = TenantContext.require();
        String phone = AcademicsService.blankToNull(request.phone());
        if (phone != null && phone.replaceAll("[^0-9]", "").length() < 8) {
            throw ApiException.badRequest("Enter a phone number with 8 to 15 digits.", "phone");
        }
        SchoolProfile profile = tenants.updateProfile(tenantId, AcademicsService.blankToNull(request.address()),
                phone, AcademicsService.blankToNull(request.contactEmail()),
                AcademicsService.blankToNull(request.udiseCode()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("address", profile.address() != null);
        details.put("phone", profile.phone() != null);
        details.put("contactEmail", profile.contactEmail() != null);
        details.put("udiseCode", profile.udiseCode());
        audit.record("school.profile_updated", "school", tenantId, details);
        return profile;
    }
}
