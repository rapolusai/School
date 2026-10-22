package com.akshara.certificates;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.certificates.CertificateForms.FamilyRequestForm;
import com.akshara.certificates.CertificateViews.CertificateDetail;
import com.akshara.certificates.CertificateViews.IdCardView;
import com.akshara.certificates.CertificateViews.MyCertificates;
import com.akshara.shared.CurrentUser;

/** The family app: a parent's (or student's) certificates and ID cards. Anyone signed in; only their own children. */
@RestController
@RequestMapping("/api/me/certificates")
@PreAuthorize("hasAuthority('dashboard.view')")
public class MyCertificatesController {

    private final FamilyCertificateService family;

    MyCertificatesController(FamilyCertificateService family) {
        this.family = family;
    }

    @GetMapping
    public MyCertificates mine() {
        return family.mine(CurrentUser.requireId());
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public CertificateDetail request(@Valid @RequestBody FamilyRequestForm form) {
        return family.request(form, CurrentUser.requireId(), CertificateAuth.actor());
    }

    @GetMapping("/{id}")
    public CertificateDetail detail(@PathVariable UUID id) {
        return family.detail(id, CurrentUser.requireId());
    }

    @GetMapping("/id-cards")
    public List<IdCardView> idCards() {
        return family.idCards(CurrentUser.requireId());
    }
}
