package com.akshara.govreport;

import java.util.ArrayList;
import java.util.List;

/** A small RFC 4180 reader for the government fields import: quoted fields, doubled quotes, CRLF or LF, a BOM. */
final class GovCsvParser {

    /** One record and the line of the file it starts on (1-based). */
    record Row(int line, List<String> values) {

        boolean isBlank() {
            return values.stream().allMatch(String::isBlank);
        }
    }

    private GovCsvParser() {
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
        int line = 1;
        int rowLine = 1;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
                continue;
            }
            if (c == '"' && field.isEmpty()) {
                inQuotes = true;
            } else if (c == ',') {
                values.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                values.add(field.toString());
                rows.add(new Row(rowLine, List.copyOf(values)));
                values.clear();
                field.setLength(0);
                line++;
                rowLine = line;
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !values.isEmpty()) {
            values.add(field.toString());
            rows.add(new Row(rowLine, List.copyOf(values)));
        }
        return rows;
    }
}
