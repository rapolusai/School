package com.akshara.platform;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.shared.ApiException;
import com.akshara.shared.JwtService;
import com.akshara.shared.LoginRateLimiter;
import com.akshara.shared.Permissions;

@RestController
@RequestMapping("/api/platform/auth")
public class PlatformAuthController {

    private final PlatformAdminRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final LoginRateLimiter rateLimiter;
    private final String dummyHash;

    public PlatformAuthController(PlatformAdminRepository admins, PasswordEncoder passwordEncoder, JwtService jwt,
            LoginRateLimiter rateLimiter) {
        this.admins = admins;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.rateLimiter = rateLimiter;
        // Checked when the email is unknown, so a wrong email takes as long as a wrong password.
        this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    public record PlatformLoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    @PostMapping("/login")
    @Transactional
    public Map<String, Object> login(@Valid @RequestBody PlatformLoginRequest request, HttpServletRequest http) {
        String key = "platform|" + http.getRemoteAddr() + "|" + request.email().toLowerCase();
        rateLimiter.check(key);
        PlatformAdmin admin = admins.findByEmail(request.email().trim()).orElse(null);
        boolean ok = passwordEncoder.matches(request.password(), admin == null ? dummyHash : admin.getPasswordHash())
                && admin != null && admin.isActive();
        if (!ok) {
            rateLimiter.recordFailure(key);
            throw ApiException.unauthorized("Those details don't match.");
        }
        rateLimiter.reset(key);
        admin.recordLogin();
        String token = jwt.issuePlatformToken(admin.getId(), admin.getName());
        Map<String, Object> me = new java.util.LinkedHashMap<>();
        me.put("id", admin.getId());
        me.put("name", admin.getName());
        me.put("email", admin.getEmail());
        me.put("roles", List.of("SUPER_ADMIN"));
        me.put("permissions", List.of(Permissions.PLATFORM_ADMIN));
        me.put("platformAdmin", true);
        me.put("tenant", null);
        return Map.of("accessToken", token, "expiresIn", jwt.ttlSeconds(), "user", me);
    }
}
