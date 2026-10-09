package com.akshara.shared;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Blocks cross-site use of the cookie-based refresh and logout endpoints. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OriginCheckFilter extends OncePerRequestFilter {

    private final AksharaProperties properties;

    public OriginCheckFilter(AksharaProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/api/auth/refresh") || path.equals("/api/auth/logout"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !properties.auth().allowedOrigins().contains(origin)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(
                    "{\"type\":\"about:blank\",\"title\":\"Not allowed\",\"status\":403,\"detail\":\"Unknown origin.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
