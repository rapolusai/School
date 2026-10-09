package com.akshara.shared;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    static final String[] PUBLIC_POSTS = {
        "/api/public/signup", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/platform/auth/login"
    };

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtService jwtService) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // bearer tokens; the refresh cookie is SameSite=Strict and Origin-checked
                .cors(cors -> cors.disable()) // the web app calls the API through its own origin
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_POSTS).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/platform/**").hasAuthority(Permissions.PLATFORM_ADMIN)
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.decoder(jwtDecoder(jwtService)).jwtAuthenticationConverter(converter()))
                        .authenticationEntryPoint(entryPoint())
                        .accessDeniedHandler(deniedHandler()))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint()).accessDeniedHandler(deniedHandler()))
                .addFilterAfter(new TenantContextFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtService jwtService) {
        return jwtService.decoder();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    private static JwtAuthenticationConverter converter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> permissions = jwt.getClaimAsStringList(JwtService.CLAIM_PERMISSIONS);
            Collection<GrantedAuthority> authorities = permissions == null ? List.of()
                    : permissions.stream().map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p)).toList();
            return authorities;
        });
        return converter;
    }

    private static AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> writeProblem(response, HttpStatus.UNAUTHORIZED, "Sign in required",
                "Your session has ended or is missing. Sign in again.");
    }

    private static AccessDeniedHandler deniedHandler() {
        return (request, response, ex) -> writeProblem(response, HttpStatus.FORBIDDEN, "Not allowed",
                "You do not have permission to do this.");
    }

    private static void writeProblem(HttpServletResponse response, HttpStatus status, String title, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":"
                + status.value() + ",\"detail\":\"" + detail + "\"}");
    }
}
