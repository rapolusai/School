package com.akshara.shared;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** An error that maps directly to an HTTP problem response. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;
    private final Map<String, String> errors;

    public ApiException(HttpStatus status, String title, String detail) {
        this(status, title, detail, Map.of());
    }

    public ApiException(HttpStatus status, String title, String detail, Map<String, String> errors) {
        super(detail);
        this.status = status;
        this.title = title;
        this.errors = errors;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public Map<String, String> errors() {
        return errors;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "Not found", what + " was not found.");
    }

    public static ApiException conflict(String detail, String field) {
        return new ApiException(HttpStatus.CONFLICT, "Already exists", detail, Map.of(field, detail));
    }

    public static ApiException badRequest(String detail, String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Check the form", detail, Map.of(field, detail));
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Sign-in failed", detail);
    }

    public static ApiException tooManyRequests() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts",
                "Too many failed sign-in attempts. Wait a few minutes and try again.");
    }
}
