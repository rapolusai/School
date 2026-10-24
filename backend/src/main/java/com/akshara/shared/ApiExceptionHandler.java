package com.akshara.shared;

import java.math.BigInteger;
import java.net.URI;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Target types for which a mismatched JSON value means "not a whole number". */
    private static final Set<Class<?>> WHOLE_NUMBERS = Set.of(int.class, Integer.class, long.class, Long.class,
            short.class, Short.class, byte.class, Byte.class, BigInteger.class);

    /** PostgreSQL states: serialization failure, deadlock detected, lock not available. */
    private static final Set<String> CONCURRENCY_STATES = Set.of("40001", "40P01", "55P03");

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handle(ApiException e) {
        ResponseEntity<ProblemDetail> response = problem(e.status(), e.title(), e.getMessage(), e.errors());
        if (e.type() != null && response.getBody() != null) {
            response.getBody().setType(URI.create(e.type()));
        }
        return response;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handle(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return problem(HttpStatus.BAD_REQUEST, "Check the form", "Some fields need attention.", errors);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, jakarta.validation.ConstraintViolationException.class})
    ResponseEntity<ProblemDetail> handleParams(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Check the request", "A request parameter is out of range.", Map.of());
    }

    /**
     * A body that is not JSON or does not fit the form. When Jackson names the field (a fraction sent to a
     * whole-number field such as an amount in paise, text sent to a number, an unknown enum value), it is put in
     * {@code errors}. Fractions are refused for whole-number fields: spring.jackson.deserialization in application.yml.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException e) {
        if (causedByTooLargeBody(e)) {
            return tooLarge();
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (e.getCause() instanceof JacksonException jackson) {
            String field = fieldPath(jackson.getPath());
            if (!field.isEmpty()) {
                boolean wholeNumber = jackson instanceof MismatchedInputException mismatch
                        && WHOLE_NUMBERS.contains(mismatch.getTargetType());
                errors.put(field, wholeNumber ? "Enter a whole number." : "This value is not valid.");
            }
        }
        return problem(HttpStatus.BAD_REQUEST, "Bad request", "The request could not be read.", errors);
    }

    /**
     * A query or path value of the wrong type is 400 naming it, except a record id in the path that is not a UUID:
     * such an address can name no record, so it is the same 404 as an id that does not exist.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        if (UUID.class.equals(e.getRequiredType()) && e.getParameter().hasParameterAnnotation(PathVariable.class)) {
            return problem(HttpStatus.NOT_FOUND, "Not found", "There is nothing at this address.", Map.of());
        }
        return problem(HttpStatus.BAD_REQUEST, "Bad request", "The request could not be read.",
                Map.of(e.getName(), "This value is not valid."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> handleMissingParameter(MissingServletRequestParameterException e) {
        return problem(HttpStatus.BAD_REQUEST, "Check the request", "A required request parameter is missing.",
                Map.of(e.getParameterName(), "This is required."));
    }

    /** A missing header or cookie the endpoint needs. A missing path variable is a mapping bug and stays a 500. */
    @ExceptionHandler(ServletRequestBindingException.class)
    ResponseEntity<ProblemDetail> handleBinding(ServletRequestBindingException e) {
        if (e instanceof MissingPathVariableException) {
            return handleUnexpected(e);
        }
        return problem(HttpStatus.BAD_REQUEST, "Bad request", "The request could not be read.", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethod(HttpRequestMethodNotSupportedException e) {
        ProblemDetail body = problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed",
                "This address does not accept " + e.getMethod() + " requests.", Map.of()).getBody();
        Set<HttpMethod> allowed = e.getSupportedHttpMethods();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (allowed != null && !allowed.isEmpty()) {
            response.allow(allowed.toArray(HttpMethod[]::new));
        }
        return response.body(body);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMediaType(HttpMediaTypeNotSupportedException e) {
        ProblemDetail body = problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type",
                "This address does not accept this kind of content.", Map.of()).getBody();
        List<MediaType> supported = e.getSupportedMediaTypes();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        if (!supported.isEmpty()) {
            response.header(HttpHeaders.ACCEPT, MediaType.toString(supported));
        }
        return response.body(body);
    }

    /** An upload larger than spring.servlet.multipart.max-file-size or max-request-size (see application.yml). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> handleTooLarge(MaxUploadSizeExceededException e) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "File too large", "Files can be at most 5 MB each.",
                Map.of("file", "Files can be at most 5 MB each."));
    }

    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    ResponseEntity<ProblemDetail> handleMultipart(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Check the file", "Attach a file and try again.",
                Map.of("file", "Attach a file and try again."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleConflict(DataIntegrityViolationException e) {
        // The database message can contain the conflicting values (emails), so it is not logged.
        log.warn("Rejected a write that conflicts with existing data");
        return alreadyExists();
    }

    /**
     * Two requests changed the same rows at once: a stale version, a lock that could not be taken, a serialization
     * failure or a deadlock. The loser is asked to try again instead of getting a 500. Logged at warn (exception types
     * only), so a bug that keeps causing it still shows up in the logs.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ProblemDetail> handleConcurrent(ConcurrencyFailureException e) {
        return concurrent(e);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<ProblemDetail> handleDenied(Exception e) {
        return problem(HttpStatus.FORBIDDEN, "Not allowed", "You do not have permission to do this.", Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", "There is nothing at this address.", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        if (causedByTooLargeBody(e)) {
            return tooLarge();
        }
        // Lock and unique-key failures that arrive untranslated, e.g. a JPA PersistenceException from a flush.
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (isConcurrencyFailure(t)) {
                return concurrent(e);
            }
            if (t instanceof DataIntegrityViolationException || isUniqueViolation(t)) {
                log.warn("Rejected a write that conflicts with existing data ({})", e.getClass().getName());
                return alreadyExists();
            }
        }
        log.error("Unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong",
                "The request failed on our side. Try again; if it keeps failing, contact support.", Map.of());
    }

    public static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail,
            Map<String, String> errors) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        body.setType(URI.create("about:blank"));
        if (!errors.isEmpty()) {
            body.setProperty("errors", errors);
        }
        return ResponseEntity.status(status).body(body);
    }

    /** A body without a Content-Length that went over {@link RequestSizeLimitFilter}'s limit while being read. */
    private static boolean causedByTooLargeBody(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RequestSizeLimitFilter.BodyTooLargeException) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<ProblemDetail> tooLarge() {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "Too large", "The request is too large.", Map.of());
    }

    private static ResponseEntity<ProblemDetail> alreadyExists() {
        return problem(HttpStatus.CONFLICT, "Already exists", "This conflicts with something that already exists.",
                Map.of());
    }

    private static ResponseEntity<ProblemDetail> concurrent(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        log.warn("Rejected a write that raced another change ({}, caused by {})", e.getClass().getName(),
                root.getClass().getName());
        return problem(HttpStatus.CONFLICT, "Changed by someone else",
                "Someone else changed this at the same time. Reload and try again.", Map.of());
    }

    static boolean isConcurrencyFailure(Throwable t) {
        return t instanceof ConcurrencyFailureException
                || t instanceof jakarta.persistence.OptimisticLockException
                || t instanceof jakarta.persistence.PessimisticLockException
                || t instanceof jakarta.persistence.LockTimeoutException
                || t instanceof org.hibernate.StaleStateException
                || t instanceof org.hibernate.exception.LockAcquisitionException
                || t instanceof SQLException sql && CONCURRENCY_STATES.contains(sql.getSQLState());
    }

    private static boolean isUniqueViolation(Throwable t) {
        return t instanceof SQLException sql && "23505".equals(sql.getSQLState());
    }

    /** Jackson's path to the bad value as a form field name: "amountPaise", "lines[0].quantity". */
    private static String fieldPath(List<JacksonException.Reference> path) {
        StringBuilder field = new StringBuilder();
        for (JacksonException.Reference reference : path) {
            if (reference.getPropertyName() != null) {
                if (!field.isEmpty()) {
                    field.append('.');
                }
                field.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                field.append('[').append(reference.getIndex()).append(']');
            }
        }
        return field.toString();
    }
}
