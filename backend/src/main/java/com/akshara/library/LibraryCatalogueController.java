package com.akshara.library;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.library.LibraryForms.CopiesForm;
import com.akshara.library.LibraryForms.CopyForm;
import com.akshara.library.LibraryForms.ImportRequest;
import com.akshara.library.LibraryForms.TitleForm;
import com.akshara.library.LibraryViews.CopyLookup;
import com.akshara.library.LibraryViews.CopyView;
import com.akshara.library.LibraryViews.ImportResult;
import com.akshara.library.LibraryViews.IsbnLookup;
import com.akshara.library.LibraryViews.LabelCopy;
import com.akshara.library.LibraryViews.TitleDetail;
import com.akshara.library.LibraryViews.TitlePage;
import com.akshara.shared.ApiExceptionHandler;

/**
 * The catalogue. Searching it and opening a title needs library.read (every member of staff); adding and changing
 * books, the ISBN check, copy look-ups, labels and the import need library.manage.
 */
@RestController
@RequestMapping("/api/library")
public class LibraryCatalogueController {

    private final CatalogueService catalogue;
    private final CatalogueImportService importer;

    LibraryCatalogueController(CatalogueService catalogue, CatalogueImportService importer) {
        this.catalogue = catalogue;
        this.importer = importer;
    }

    @GetMapping("/titles")
    @PreAuthorize(LibraryAuth.READ)
    public TitlePage search(@RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 100) String subject,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(CatalogueService.MAX_PAGE_SIZE) int size) {
        return catalogue.search(q, subject, page, size, LoanMath.today());
    }

    @GetMapping("/subjects")
    @PreAuthorize(LibraryAuth.READ)
    public List<String> subjects() {
        return catalogue.subjects();
    }

    @GetMapping("/titles/{id}")
    @PreAuthorize(LibraryAuth.READ)
    public TitleDetail detail(@PathVariable UUID id) {
        return catalogue.detail(id, LibraryAuth.canManage(), LoanMath.today());
    }

    @PostMapping("/titles")
    @PreAuthorize(LibraryAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public TitleDetail create(@Valid @RequestBody TitleForm form) {
        return catalogue.create(form, LibraryAuth.actor(), LoanMath.today());
    }

    @PutMapping("/titles/{id}")
    @PreAuthorize(LibraryAuth.MANAGE)
    public TitleDetail update(@PathVariable UUID id, @Valid @RequestBody TitleForm form) {
        return catalogue.update(id, form, LibraryAuth.actor(), LoanMath.today());
    }

    @DeleteMapping("/titles/{id}")
    @PreAuthorize(LibraryAuth.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        catalogue.delete(id, LibraryAuth.actor());
    }

    @PostMapping("/titles/{id}/copies")
    @PreAuthorize(LibraryAuth.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public TitleDetail addCopies(@PathVariable UUID id, @Valid @RequestBody CopiesForm form) {
        return catalogue.addCopies(id, form, LibraryAuth.actor(), LoanMath.today());
    }

    @PutMapping("/copies/{id}")
    @PreAuthorize(LibraryAuth.MANAGE)
    public CopyView updateCopy(@PathVariable UUID id, @Valid @RequestBody CopyForm form) {
        return catalogue.updateCopy(id, form, LibraryAuth.actor(), LoanMath.today());
    }

    /** A copy by its accession number (as a barcode scanner types it), with its loan or hold. */
    @GetMapping("/copies/lookup")
    @PreAuthorize(LibraryAuth.MANAGE)
    public CopyLookup lookup(@RequestParam @Size(max = 30) String accessionNo) {
        return catalogue.lookup(accessionNo, LoanMath.today());
    }

    /** Copies for a label sheet: one title's, a range of accession numbers, or matching a search (500 at most). */
    @GetMapping("/labels")
    @PreAuthorize(LibraryAuth.MANAGE)
    public List<LabelCopy> labels(@RequestParam(required = false) UUID titleId,
            @RequestParam(required = false) @Size(max = 30) String from,
            @RequestParam(required = false) @Size(max = 30) String to,
            @RequestParam(required = false) @Size(max = 100) String q) {
        return catalogue.labelCopies(titleId, from, to, q);
    }

    /** Checks an ISBN offline and finds it in the catalogue. Never calls an outside service. */
    @GetMapping("/isbn")
    @PreAuthorize(LibraryAuth.MANAGE)
    public IsbnLookup isbn(@RequestParam @Size(max = 40) String value) {
        return catalogue.isbn(value);
    }

    /**
     * Checks (dryRun=true, the default) or imports CSV rows. A real import with any bad row writes nothing and
     * answers 400 with the row problems.
     */
    @PostMapping("/titles/import")
    @PreAuthorize(LibraryAuth.MANAGE)
    public ResponseEntity<?> importCsv(@RequestParam(defaultValue = "true") boolean dryRun,
            @Valid @RequestBody ImportRequest request) {
        ImportResult result = importer.run(request.csv(), dryRun, LibraryAuth.actor(), LoanMath.today());
        if (!dryRun && !result.committed()) {
            ResponseEntity<ProblemDetail> problem = ApiExceptionHandler.problem(HttpStatus.BAD_REQUEST,
                    "Check the file", result.invalidRows() + (result.invalidRows() == 1 ? " row needs" : " rows need")
                            + " attention. Nothing was imported.", Map.of());
            problem.getBody().setProperty("result", result);
            return problem;
        }
        return ResponseEntity.ok(result);
    }
}
