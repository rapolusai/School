package com.akshara.shared;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/** Copies the school id from the verified token into {@link TenantContext} for the length of the request. */
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            CurrentUser.token()
                    .map(jwt -> jwt.getClaimAsString(JwtService.CLAIM_TENANT))
                    .map(UUID::fromString)
                    .ifPresent(TenantContext::set);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
