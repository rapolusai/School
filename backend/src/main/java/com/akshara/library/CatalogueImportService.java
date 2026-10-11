package com.akshara.library;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.library.LibraryViews.ImportError;
import com.akshara.library.LibraryViews.ImportPreviewRow;
import com.akshara.library.LibraryViews.ImportResult;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Adds many books at once from CSV text, such as the school's old accession register. Only the title column is
 * required. A row whose ISBN (or, without an ISBN, whose title and authors) matches a title already in the catalogue
 * or an earlier row adds copies to that title instead of a second title. A row may give its copy's own accession
 * number (then it describes one copy); other copies get the school's next numbers. Every row is checked first; a dry
 * run only reports, with a preview, and a real run writes every row in one transaction or, when any row has a
 * problem, writes nothing.
 */
@Service
public class CatalogueImportService {

    public static final int MAX_ROWS = 2_000;
    /** The most copies one file may add. */
    public static final int MAX_COPIES = 10_000;
    static final int MAX_REPORTED_ERRORS = 500;
    static final int PREVIEW_ROWS = 50;

    /** The columns a file may have, in the documented order. Only title is required. */
    public static final List<String> COLUMNS = List.of("title", "isbn", "subtitle", "authors", "publisher", "year",
            "edition", "language", "subject", "call_number", "shelf_location", "copies", "price", "source",
            "accession_no", "acquired_on");

    private static final Pattern ACCESSION = Pattern.compile("^[A-Z0-9/-]{1,30}$");
    private static final Pattern RUPEES = Pattern.compile("^[0-9]{1,7}(\\.[0-9]{1,2})?$");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT));

    /** A checked row: the title it describes and the copies it adds. */
    private record Parsed(int line, Title.Details details, int copies, Long pricePaise, CopySource source,
            String accessionNo, LocalDate acquiredOn, String key) {
    }

    private final TitleRepository titles;
    private final CopyRepository copies;
    private final CatalogueService catalogue;
    private final HoldService holds;
    private final AuditService audit;

    CatalogueImportService(TitleRepository titles, CopyRepository copies, CatalogueService catalogue,
            HoldService holds, AuditService audit) {
        this.titles = titles;
        this.copies = copies;
        this.catalogue = catalogue;
        this.holds = holds;
        this.audit = audit;
    }

    @Transactional
    public ImportResult run(String csv, boolean dryRun, Actor actor, LocalDate today) {
        TenantContext.require();
        List<CsvReader.Row> rows;
        try {
            rows = CsvReader.parse(csv).stream().filter(r -> !r.isBlank()).toList();
        } catch (CsvReader.CsvException e) {
            throw ApiException.badRequest(e.getMessage(), "csv");
        }
        if (rows.isEmpty()) {
            throw ApiException.badRequest("The file is empty. Start with the header row.", "csv");
        }
        Map<String, Integer> columns = header(rows.getFirst());
        List<String> ignored = columns.keySet().stream().filter(c -> !COLUMNS.contains(c)).toList();
        List<CsvReader.Row> data = rows.subList(1, rows.size());
        if (data.isEmpty()) {
            throw ApiException.badRequest("The file has a header but no books.", "csv");
        }
        if (data.size() > MAX_ROWS) {
            throw ApiException.badRequest("A file can have at most " + String.format(Locale.ROOT, "%,d", MAX_ROWS)
                    + " rows. This one has " + String.format(Locale.ROOT, "%,d", data.size()) + ".", "csv");
        }

        List<ImportError> errors = new ArrayList<>();
        Set<Integer> invalidLines = new HashSet<>();
        List<Parsed> parsed = validate(data, columns, today, errors, invalidLines);
        int totalCopies = parsed.stream().mapToInt(Parsed::copies).sum();
        if (totalCopies > MAX_COPIES) {
            throw ApiException.badRequest("A file can add at most " + String.format(Locale.ROOT, "%,d", MAX_COPIES)
                    + " copies. Split it into smaller files.", "csv");
        }

        // Titles already in the catalogue that rows add copies to.
        Map<String, Title> existing = new HashMap<>();
        Set<String> isbns = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Parsed p : parsed) {
            if (p.details().isbn13() != null) {
                isbns.add(p.details().isbn13());
            } else {
                names.add(p.details().title().toLowerCase(Locale.ROOT));
            }
        }
        if (!isbns.isEmpty()) {
            titles.findByIsbn13In(isbns).forEach(t -> existing.put(isbnKey(t.getIsbn13()), t));
        }
        if (!names.isEmpty()) {
            titles.withoutIsbnNamed(names).forEach(t -> existing.putIfAbsent(nameKey(t.getTitle(), t.getAuthors()), t));
        }

        int invalid = invalidLines.size();
        List<ImportError> reported = errors.size() > MAX_REPORTED_ERRORS ? errors.subList(0, MAX_REPORTED_ERRORS)
                : errors;
        Set<String> seenKeys = new HashSet<>();
        int titlesToCreate = 0;
        List<ImportPreviewRow> preview = new ArrayList<>();
        for (Parsed p : parsed) {
            boolean known = existing.containsKey(p.key()) || seenKeys.contains(p.key());
            seenKeys.add(p.key());
            if (!known) {
                titlesToCreate++;
            }
            if (preview.size() < PREVIEW_ROWS) {
                preview.add(new ImportPreviewRow(p.line(), p.details().title(), p.details().isbn13(),
                        p.details().authors(), p.copies(), known, p.accessionNo()));
            }
        }
        if (dryRun || invalid > 0) {
            return new ImportResult(dryRun, false, data.size(), data.size() - invalid, invalid,
                    invalid > 0 ? 0 : titlesToCreate, invalid > 0 ? 0 : totalCopies, List.copyOf(reported), ignored,
                    List.copyOf(preview));
        }

        // First the titles and the copies with their own accession numbers, then numbered copies, row by row.
        Map<String, Title> byKey = new HashMap<>(existing);
        List<Copy> numbered = new ArrayList<>();
        int created = 0;
        for (Parsed p : parsed) {
            Title title = byKey.get(p.key());
            if (title == null) {
                title = titles.save(new Title(p.details()));
                byKey.put(p.key(), title);
                created++;
            }
            if (p.accessionNo() != null) {
                numbered.add(new Copy(title.getId(), p.accessionNo(), p.pricePaise(), source(p), p.acquiredOn()));
            }
        }
        titles.flush();
        copies.saveAllAndFlush(numbered);
        List<Copy> added = new ArrayList<>(numbered);
        for (Parsed p : parsed) {
            if (p.accessionNo() == null) {
                added.addAll(catalogue.addCopies(byKey.get(p.key()), p.copies(), p.pricePaise(), source(p),
                        p.acquiredOn()));
            }
        }
        // New copies of titles already in the catalogue go to members waiting for them.
        Set<UUID> existingIds = new HashSet<>();
        existing.values().forEach(t -> existingIds.add(t.getId()));
        for (Copy copy : added) {
            if (existingIds.contains(copy.getTitleId())) {
                holds.offer(copy, today);
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rows", parsed.size());
        details.put("titlesCreated", created);
        details.put("copiesCreated", added.size());
        audit.record(actor, "library_titles.imported", "library_title", null, details);
        return new ImportResult(false, true, data.size(), parsed.size(), 0, created, added.size(), List.of(), ignored,
                List.copyOf(preview));
    }

    private static CopySource source(Parsed p) {
        return p.source() == null ? CopySource.PURCHASE : p.source();
    }

    private static Map<String, Integer> header(CsvReader.Row row) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < row.values().size(); i++) {
            String name = row.values().get(i).trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
            if (name.isEmpty()) {
                continue;
            }
            if (columns.putIfAbsent(name, i) != null) {
                throw ApiException.badRequest("The column " + name + " appears twice in the header.", "csv");
            }
        }
        if (!columns.containsKey("title")) {
            throw ApiException.badRequest("The header is missing title. The first row must name the columns.", "csv");
        }
        return columns;
    }

    private List<Parsed> validate(List<CsvReader.Row> data, Map<String, Integer> columns, LocalDate today,
            List<ImportError> errors, Set<Integer> invalidLines) {
        Set<String> accessionNos = new HashSet<>();
        for (CsvReader.Row row : data) {
            String value = cell(row, columns, "accession_no").toUpperCase(Locale.ROOT);
            if (!value.isEmpty()) {
                accessionNos.add(value);
            }
        }
        Set<String> taken = new HashSet<>(accessionNos.isEmpty() ? List.of()
                : copies.existingAccessionNos(accessionNos));
        Map<String, Integer> seenAccession = new HashMap<>();

        List<Parsed> parsed = new ArrayList<>();
        for (CsvReader.Row row : data) {
            int line = row.line();
            List<ImportError> rowErrors = new ArrayList<>();

            String title = text(row, columns, "title", 300, rowErrors);
            if (title != null && title.isEmpty()) {
                rowErrors.add(new ImportError(line, "title", "Fill in the title."));
            }
            String isbn13 = null;
            String isbn10 = null;
            String rawIsbn = cell(row, columns, "isbn");
            if (!rawIsbn.isEmpty()) {
                var isbn = Isbn.parse(rawIsbn);
                if (isbn.isEmpty()) {
                    rowErrors.add(new ImportError(line, "isbn", Isbn.problem(rawIsbn)));
                } else {
                    isbn13 = isbn.get().isbn13();
                    isbn10 = isbn.get().isbn10();
                }
            }
            String subtitle = text(row, columns, "subtitle", 300, rowErrors);
            String authors = text(row, columns, "authors", 500, rowErrors);
            String publisher = text(row, columns, "publisher", 200, rowErrors);
            Integer year = null;
            String yearText = cell(row, columns, "year");
            if (!yearText.isEmpty()) {
                year = parseInt(yearText);
                if (year == null || year < 1400 || year > 2100) {
                    rowErrors.add(new ImportError(line, "year", "Write the year as four digits, such as 2019."));
                    year = null;
                }
            }
            String edition = text(row, columns, "edition", 60, rowErrors);
            String language = text(row, columns, "language", 40, rowErrors);
            String subject = text(row, columns, "subject", 100, rowErrors);
            String callNumber = text(row, columns, "call_number", 40, rowErrors);
            String shelf = text(row, columns, "shelf_location", 60, rowErrors);

            String accessionNo = cell(row, columns, "accession_no").toUpperCase(Locale.ROOT);
            if (accessionNo.isEmpty()) {
                accessionNo = null;
            } else if (!ACCESSION.matcher(accessionNo).matches()) {
                rowErrors.add(new ImportError(line, "accession_no",
                        "Use up to 30 letters, digits, / and - for the accession number."));
            } else {
                Integer firstLine = seenAccession.putIfAbsent(accessionNo, line);
                if (firstLine != null) {
                    rowErrors.add(new ImportError(line, "accession_no", "Also used on row " + firstLine + "."));
                } else if (taken.contains(accessionNo)) {
                    rowErrors.add(new ImportError(line, "accession_no",
                            "Accession number " + accessionNo + " is already used."));
                }
            }

            int count = 1;
            String countText = cell(row, columns, "copies");
            if (!countText.isEmpty()) {
                Integer n = parseInt(countText);
                if (n == null || n < 0 || n > LibraryForms.MAX_COPIES_AT_ONCE) {
                    rowErrors.add(new ImportError(line, "copies",
                            "Write the number of copies, from 0 to " + LibraryForms.MAX_COPIES_AT_ONCE + "."));
                } else {
                    count = n;
                }
            }
            if (accessionNo != null && count != 1) {
                rowErrors.add(new ImportError(line, "copies",
                        "A row with an accession number is one copy. Put each copy on its own row."));
            }

            Long pricePaise = null;
            String priceText = cell(row, columns, "price").replace(",", "").replace("₹", "").replaceFirst(
                    "(?i)^rs\\.?\\s*", "").trim();
            if (!priceText.isEmpty()) {
                if (!RUPEES.matcher(priceText).matches()) {
                    rowErrors.add(new ImportError(line, "price", "Write the price in rupees, such as 250 or 250.50."));
                } else {
                    pricePaise = new BigDecimal(priceText).movePointRight(2).longValueExact();
                    if (pricePaise > LibraryForms.MAX_PRICE_PAISE) {
                        rowErrors.add(new ImportError(line, "price", "The price is too high."));
                        pricePaise = null;
                    }
                }
            }

            CopySource source = null;
            String sourceText = cell(row, columns, "source").toUpperCase(Locale.ROOT);
            if (!sourceText.isEmpty()) {
                source = switch (sourceText) {
                    case "PURCHASE", "PURCHASED", "BOUGHT", "P" -> CopySource.PURCHASE;
                    case "DONATION", "DONATED", "GIFT", "D" -> CopySource.DONATION;
                    default -> null;
                };
                if (source == null) {
                    rowErrors.add(new ImportError(line, "source", "Use PURCHASE or DONATION."));
                }
            }

            LocalDate acquiredOn = null;
            String acquiredText = cell(row, columns, "acquired_on");
            if (!acquiredText.isEmpty()) {
                acquiredOn = parseDate(acquiredText);
                if (acquiredOn == null) {
                    rowErrors.add(new ImportError(line, "acquired_on",
                            "Write the date as YYYY-MM-DD or DD-MM-YYYY."));
                } else if (acquiredOn.isAfter(today)) {
                    rowErrors.add(new ImportError(line, "acquired_on", "The date cannot be in the future."));
                    acquiredOn = null;
                }
            }

            if (!rowErrors.isEmpty()) {
                invalidLines.add(line);
                errors.addAll(rowErrors);
                continue;
            }
            Title.Details details = new Title.Details(isbn13, isbn10, title, subtitle, authors, publisher, year,
                    edition, language, subject, callNumber, shelf);
            String key = isbn13 != null ? isbnKey(isbn13) : nameKey(title, authors);
            parsed.add(new Parsed(line, details, count, pricePaise, source, accessionNo, acquiredOn, key));
        }
        return parsed;
    }

    private static String isbnKey(String isbn13) {
        return "isbn:" + isbn13;
    }

    private static String nameKey(String title, String authors) {
        return "name:" + title.trim().toLowerCase(Locale.ROOT) + "|"
                + (authors == null ? "" : authors.trim().toLowerCase(Locale.ROOT));
    }

    /** The trimmed cell, null when empty; a row error when longer than {@code max}. */
    private static String text(CsvReader.Row row, Map<String, Integer> columns, String column, int max,
            List<ImportError> rowErrors) {
        String value = cell(row, columns, column);
        if (value.length() > max) {
            rowErrors.add(new ImportError(row.line(), column, "Use at most " + max + " characters."));
            return null;
        }
        return "title".equals(column) ? value : (value.isEmpty() ? null : value);
    }

    private static String cell(CsvReader.Row row, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        if (index == null || index >= row.values().size()) {
            return "";
        }
        return row.values().get(index).trim();
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(value.trim(), f);
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        return null;
    }
}
