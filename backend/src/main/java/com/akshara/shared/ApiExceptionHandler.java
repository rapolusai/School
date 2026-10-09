package com.akshara.shared;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handle(ApiException e) {
        return problem(e.status(), e.title(), e.getMessage(), e.errors());
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

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> handleUnreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", "The request could not be read.", Map.of());
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
        return problem(HttpStatus.CONFLICT, "Already exists", "This conflicts with something that already exists.",
                Map.of());
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
}
