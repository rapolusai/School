package com.akshara.onboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.audit.AuditService.Actor;
import com.akshara.platform.Board;
import com.akshara.platform.Plan;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantStatus;
import com.akshara.platform.TenantSummary;
import com.akshara.shared.CurrentUser;

/** A Super Admin adds a school directly (sales-led onboarding). It starts active, with no trial. */
@RestController
public class PlatformSchoolController {

    private final SchoolProvisioning provisioning;
    private final TenantDirectory tenants;

    public PlatformSchoolController(SchoolProvisioning provisioning, TenantDirectory tenants) {
        this.provisioning = provisioning;
        this.tenants = tenants;
    }

    public record CreateSchoolRequest(
            @NotBlank @Size(max = 200) String schoolName,
            @NotBlank @Pattern(regexp = SignupController.CODE_PATTERN, message = SignupController.CODE_MESSAGE)
            String schoolCode,
            @NotNull Board board,
            @Size(max = 100) String city,
            @NotNull Plan plan,
            @NotBlank @Size(max = 200) String adminName,
            @NotBlank @Email @Size(max = 254) String adminEmail,
            @NotBlank @Size(min = 10, max = 200, message = "Use at least 10 characters.") String password) {
    }

    @PostMapping("/api/platform/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantSummary create(@Valid @RequestBody CreateSchoolRequest request) {
        Actor actor = new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Platform admin"));
        var provisioned = provisioning.provision(new SchoolProvisioning.NewSchool(request.schoolName(),
                request.schoolCode(), request.board(), request.city(), request.plan(), request.adminName(),
                request.adminEmail(), request.password()), TenantStatus.ACTIVE, null, actor);
        return tenants.summary(provisioned.tenant().id()).orElseThrow();
    }
}
