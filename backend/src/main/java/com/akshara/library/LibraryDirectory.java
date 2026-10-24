package com.akshara.library;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;

/** What other modules may ask the library: how many books a student or staff member still has out. */
@Service
public class LibraryDirectory {

    private final LoanRepository loans;

    LibraryDirectory(LoanRepository loans) {
        this.loans = loans;
    }

    @Transactional(readOnly = true)
    public int booksOutOfStudent(UUID studentId) {
        TenantContext.require();
        return loans.openOfStudent(studentId).size();
    }

    @Transactional(readOnly = true)
    public int booksOutOfStaff(UUID userId) {
        TenantContext.require();
        return loans.openOfStaff(userId).size();
    }
}
