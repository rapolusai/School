package com.akshara.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.LockAcquisitionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;

/** Races and lock failures are a 409 "try again"; anything else stays a 500. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void aStaleVersionIsAConflict() {
        ResponseEntity<ProblemDetail> response = handler.handleConcurrent(
                new ObjectOptimisticLockingFailureException("health.medicine", "id"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getTitle()).isEqualTo("Changed by someone else");
    }

    @Test
    void aLockOrSerializationFailureIsAConflict() {
        assertThat(handler.handleConcurrent(new CannotAcquireLockException("lock")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        SQLException serialization = new SQLException("could not serialize access", "40001");
        assertThat(handler.handleUnexpected(new RuntimeException(serialization)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void untranslatedJpaFailuresAreRecognisedThroughTheirCause() {
        assertThat(handler.handleUnexpected(new PersistenceException(
                new LockAcquisitionException("lock", new SQLException("lock", "55P03")))).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.handleUnexpected(new OptimisticLockException("stale")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<ProblemDetail> unique = handler.handleUnexpected(
                new PersistenceException(new SQLException("duplicate key", "23505")));
        assertThat(unique.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(unique.getBody().getTitle()).isEqualTo("Already exists");
    }

    @Test
    void genuineBugsStayA500() {
        assertThat(handler.handleUnexpected(new IllegalStateException("bug")).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(handler.handleUnexpected(new PersistenceException(new SQLException("syntax", "42601")))
                .getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
