package com.akshara.certificates;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.CurrentUser;

/** Who is using the certificate screens: the audit actor and the permission expressions of the controllers. */
final class CertificateAuth {

    static final String READ = "hasAuthority('certificates.read')";
    static final String ISSUE = "hasAuthority('certificates.issue')";
    static final String SIGN = "hasAuthority('certificates.sign')";

    private CertificateAuth() {
    }

    static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Staff"));
    }

    static Set<String> permissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? Set.of() : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
