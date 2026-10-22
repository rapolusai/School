package com.akshara.certificates;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.akshara.certificates.CertificateForms.CardBatchForm;
import com.akshara.certificates.CertificateForms.CardForm;
import com.akshara.certificates.CertificateForms.ReissueForm;
import com.akshara.certificates.CertificateViews.CardBatchResult;
import com.akshara.certificates.CertificateViews.CardRegister;
import com.akshara.certificates.CertificateViews.IdCardView;
import com.akshara.certificates.CertificateViews.PhotoView;
import com.akshara.files.FileUploads;
import com.akshara.shared.ApiException;

/** The ID card register and photos (docs/api/phase-2-certificates.md). */
@RestController
@RequestMapping("/api/certificates/id-cards")
@Validated
public class IdCardController {

    private final IdCardService cards;

    IdCardController(IdCardService cards) {
        this.cards = cards;
    }

    @GetMapping
    @PreAuthorize(CertificateAuth.READ)
    public CardRegister register(@RequestParam(defaultValue = "STUDENT") SubjectType subjectType,
            @RequestParam(required = false) UUID sectionId,
            @RequestParam(defaultValue = "false") boolean all,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(IdCardService.MAX_PAGE_SIZE) int size) {
        return cards.register(subjectType, sectionId, all, page, size);
    }

    @PostMapping
    @PreAuthorize(CertificateAuth.ISSUE)
    @ResponseStatus(HttpStatus.CREATED)
    public IdCardView issue(@Valid @RequestBody CardForm form) {
        return cards.issue(form, CertificateAuth.actor());
    }

    @PostMapping("/batch")
    @PreAuthorize(CertificateAuth.ISSUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CardBatchResult batch(@Valid @RequestBody CardBatchForm form) {
        return cards.issueBatch(form, CertificateAuth.actor());
    }

    @GetMapping("/{id}")
    @PreAuthorize(CertificateAuth.READ)
    public IdCardView detail(@PathVariable UUID id) {
        return cards.detail(id);
    }

    @PostMapping("/{id}/reissue")
    @PreAuthorize(CertificateAuth.ISSUE)
    @ResponseStatus(HttpStatus.CREATED)
    public IdCardView reissue(@PathVariable UUID id, @Valid @RequestBody ReissueForm form) {
        return cards.reissue(id, form, CertificateAuth.actor());
    }

    @GetMapping("/photos/{subjectType}/{personId}")
    @PreAuthorize(CertificateAuth.READ)
    public PhotoView photo(@PathVariable SubjectType subjectType, @PathVariable UUID personId) {
        return cards.photo(subjectType, personId).orElseThrow(() -> ApiException.notFound("photo"));
    }

    @PostMapping(value = "/photos/{subjectType}/{personId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(CertificateAuth.ISSUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PhotoView uploadPhoto(@PathVariable SubjectType subjectType, @PathVariable UUID personId,
            @RequestPart("file") MultipartFile file) {
        return cards.uploadPhoto(subjectType, personId, FileUploads.read(file, "file"), CertificateAuth.actor());
    }
}
