package com.akshara.shared;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** An error that maps directly to an HTTP problem response. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;
    private final Map<String, String> errors;
    private final String type;
    private final Map<String, Object> properties;

    public ApiException(HttpStatus status, String title, String detail) {
        this(status, title, detail, Map.of());
    }

    public ApiException(HttpStatus status, String title, String detail, Map<String, String> errors) {
        this(status, title, detail, errors, null);
    }

    /**
     * @param type the problem type URI the web app can recognise (RFC 9457), or null for {@code about:blank}
     */
    public ApiException(HttpStatus status, String title, String detail, Map<String, String> errors, String type) {
        this(status, title, detail, errors, type, Map.of());
    }

    /**
     * @param properties extra members of the problem body (RFC 9457 extensions), such as the plan feature a request
     *        needs
     */
    public ApiException(HttpStatus status, String title, String detail, Map<String, String> errors, String type,
            Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.title = title;
        this.errors = errors;
        this.type = type;
        this.properties = properties;
    }

    /** Extra members of the problem body; empty for most problems. */
    public Map<String, Object> properties() {
        return properties;
    }

    /** The problem type URI, or null for {@code about:blank}. */
    public String type() {
        return type;
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
