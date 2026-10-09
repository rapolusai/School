package com.akshara.onboarding;

import java.time.Instant;
import java.util.UUID;

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

import com.akshara.platform.Board;
import com.akshara.platform.Plan;
import com.akshara.platform.TenantStatus;
import com.akshara.shared.AksharaProperties;

/** Self-service sign-up: a school starts on a free trial of the Starter plan. */
@RestController
public class SignupController {

    static final String CODE_PATTERN = "^[a-z][a-z0-9-]{2,39}$";
    static final String CODE_MESSAGE = "Use 3 to 40 lowercase letters, numbers or hyphens, starting with a letter.";

    private final SchoolProvisioning provisioning;
    private final AksharaProperties properties;

    public SignupController(SchoolProvisioning provisioning, AksharaProperties properties) {
        this.provisioning = provisioning;
        this.properties = properties;
    }

    public record SignupRequest(
            @NotBlank @Size(max = 200) String schoolName,
            @NotBlank @Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE) String schoolCode,
            @NotNull Board board,
            @Size(max = 100) String city,
            @NotBlank @Size(max = 200) String adminName,
            @NotBlank @Email @Size(max = 254) String adminEmail,
            @NotBlank @Size(min = 10, max = 200, message = "Use at least 10 characters.") String password) {
    }

    public record SignupResponse(UUID tenantId, String schoolCode, Instant trialEndsAt) {
    }

    @PostMapping("/api/public/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        Instant trialEndsAt = Instant.now().plus(properties.trial().length());
        var provisioned = provisioning.provision(new SchoolProvisioning.NewSchool(request.schoolName(),
                request.schoolCode(), request.board(), request.city(), Plan.STARTER, request.adminName(),
                request.adminEmail(), request.password()), TenantStatus.TRIAL, trialEndsAt, null);
        return new SignupResponse(provisioned.tenant().id(), provisioned.tenant().code(),
                provisioned.tenant().trialEndsAt());
    }
}
