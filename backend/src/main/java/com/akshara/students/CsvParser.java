package com.akshara.students;

import java.util.ArrayList;
import java.util.List;

/**
 * A small RFC 4180 reader: comma separated, fields optionally in double quotes, quotes inside quoted fields doubled
 * (""), commas and line breaks allowed inside quotes, and LF, CRLF or CR line endings. A leading byte order mark is
 * ignored. Values are returned as written (not trimmed).
 */
final class CsvParser {

    /** One record and the line of the file it starts on (1-based). */
    record Row(int line, List<String> values) {

        boolean isBlank() {
            return values.stream().allMatch(String::isBlank);
        }
    }

    static final class CsvException extends RuntimeException {

        CsvException(String message) {
            super(message);
        }
    }

    private CsvParser() {
    }

    static List<Row> parse(String text) {
        List<Row> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return rows;
        }
        int start = text.charAt(0) == '﻿' ? 1 : 0;
        List<String> values = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean afterQuote = false; // a quoted field just closed; only a separator may follow
        boolean fieldStarted = false;
        int line = 1;
        int rowLine = 1;
        int quoteLine = 1;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                        afterQuote = true;
                    }
                } else {
                    if (c == '\n' || (c == '\r' && !(i + 1 < text.length() && text.charAt(i + 1) == '\n'))) {
                        line++;
                    }
                    field.append(c);
                }
                continue;
            }
            if (c == ',') {
                values.add(field.toString());
                field.setLength(0);
                afterQuote = false;
                fieldStarted = true;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                values.add(field.toString());
                rows.add(new Row(rowLine, List.copyOf(values)));
                values.clear();
                field.setLength(0);
                afterQuote = false;
                fieldStarted = false;
                line++;
                rowLine = line;
            } else if (afterQuote) {
                if (c != ' ' && c != '\t') {
                    throw new CsvException("Line " + line + ": only a comma may follow a closing quote.");
                }
            } else if (c == '"' && field.toString().isBlank()) {
                field.setLength(0);
                inQuotes = true;
                quoteLine = line;
                fieldStarted = true;
            } else {
                field.append(c);
                fieldStarted = true;
            }
        }
        if (inQuotes) {
            throw new CsvException("Line " + quoteLine + ": a quoted value is not closed.");
        }
        if (fieldStarted || !field.isEmpty() || !values.isEmpty() || afterQuote) {
            values.add(field.toString());
            rows.add(new Row(rowLine, List.copyOf(values)));
        }
        return rows;
    }
}
