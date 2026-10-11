package com.akshara.lessons;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.akshara.shared.ApiException;

/**
 * The common part of the syllabus and question bank imports: reading the CSV text, the header (column names in any
 * order and case, spaces or dashes read as underscores), the row limit and reading a cell.
 */
final class LessonsImport {

    static final int MAX_ROWS = 2_000;
    static final int MAX_REPORTED_ERRORS = 500;
    static final int PREVIEW_ROWS = 50;

    /** The data rows of a file with the position of each named column, and the columns that are not used. */
    record Sheet(Map<String, Integer> columns, List<LessonsCsvReader.Row> rows, List<String> ignored) {

        String cell(LessonsCsvReader.Row row, String column) {
            Integer index = columns.get(column);
            if (index == null || index >= row.values().size()) {
                return "";
            }
            return row.values().get(index).strip();
        }
    }

    private LessonsImport() {
    }

    static Sheet read(String csv, List<String> known, List<String> required, String what) {
        List<LessonsCsvReader.Row> rows;
        try {
            rows = LessonsCsvReader.parse(csv).stream().filter(r -> !r.isBlank()).toList();
        } catch (LessonsCsvReader.CsvException e) {
            throw ApiException.badRequest(e.getMessage(), "csv");
        }
        if (rows.isEmpty()) {
            throw ApiException.badRequest("The file is empty. Start with the header row.", "csv");
        }
        Map<String, Integer> columns = new LinkedHashMap<>();
        LessonsCsvReader.Row header = rows.getFirst();
        for (int i = 0; i < header.values().size(); i++) {
            String name = header.values().get(i).strip().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
            if (name.isEmpty()) {
                continue;
            }
            if (columns.putIfAbsent(name, i) != null) {
                throw ApiException.badRequest("The column " + name + " appears twice in the header.", "csv");
            }
        }
        List<String> missing = required.stream().filter(c -> !columns.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("The header is missing " + String.join(", ", missing)
                    + ". The first row must name the columns.", "csv");
        }
        List<LessonsCsvReader.Row> data = rows.subList(1, rows.size());
        if (data.isEmpty()) {
            throw ApiException.badRequest("The file has a header but no " + what + ".", "csv");
        }
        if (data.size() > MAX_ROWS) {
            throw ApiException.badRequest("A file can have at most " + String.format(Locale.ROOT, "%,d", MAX_ROWS)
                    + " rows. This one has " + String.format(Locale.ROOT, "%,d", data.size()) + ".", "csv");
        }
        List<String> ignored = columns.keySet().stream().filter(c -> !known.contains(c)).toList();
        return new Sheet(columns, data, ignored);
    }

    /** A class or subject name as typed, for matching ("class 5" matches "Class 5"). */
    static String key(String name) {
        return name == null ? "" : name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
