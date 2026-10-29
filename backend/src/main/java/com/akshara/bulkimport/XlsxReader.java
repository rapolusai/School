package com.akshara.bulkimport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A small, defensive reader for Excel workbooks (.xlsx, Office Open XML SpreadsheetML), written with java.util.zip
 * and StAX so no library is needed. It reads only cell values: shared strings, inline strings, numbers, booleans and
 * dates stored as serial numbers (cells whose number format is a date come back as YYYY-MM-DD). Formulas are never
 * evaluated: a formula cell gives the value Excel last saved for it.
 *
 * <p>Untrusted files are expected. The reader refuses: files over {@link Limits#maxFileBytes}, anything that is not
 * a zip, more than {@link Limits#maxEntries} entries, entry names with path tricks ({@code ..}, absolute paths,
 * backslashes, NUL), duplicate entries, more than {@link Limits#maxEntryBytes} uncompressed in any entry or
 * {@link Limits#maxTotalBytes} in all (counted while inflating, so a zip bomb stops early whatever its headers
 * claim), any DTD (so no entity expansion and no external entities), more than {@link Limits#maxSheets} sheets,
 * {@link Limits#maxRowsPerSheet} rows in a sheet, {@link Limits#maxColumns} columns, or cells longer than
 * {@link Limits#maxCellChars} characters.
 */
final class XlsxReader {

    /** Why a file cannot be read, in words for the person who uploaded it. */
    static final class XlsxException extends RuntimeException {

        XlsxException(String message) {
            super(message);
        }
    }

    record Limits(long maxFileBytes, int maxEntries, long maxEntryBytes, long maxTotalBytes, int maxSheets,
            int maxRowsPerSheet, int maxColumns, int maxCellChars, int maxSharedStrings) {

        static final Limits DEFAULT = new Limits(5L * 1024 * 1024, 2_000, 40L * 1024 * 1024, 120L * 1024 * 1024, 250,
                10_000, 100, 5_000, 500_000);
    }

    /** One row: its number in the sheet (1-based, as Excel shows it) and its cells, "" where empty. */
    record Row(int number, List<String> cells) {

        boolean isBlank() {
            return cells.stream().allMatch(String::isBlank);
        }

        String cell(int index) {
            return index < cells.size() ? cells.get(index) : "";
        }
    }

    record Sheet(String name, List<Row> rows) {
    }

    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String STRICT_REL_NS = "http://purl.oclc.org/ooxml/officeDocument/relationships";
    private static final LocalDate EPOCH_1900 = LocalDate.of(1899, 12, 30);
    private static final LocalDate EPOCH_1904 = LocalDate.of(1904, 1, 1);
    private static final int MAX_EXCEL_ROW = 1_048_576;
    /** Digits before or after the decimal point beyond which a number cell is kept as written. */
    private static final int MAX_NUMBER_DIGITS = 30;

    private final Limits limits;
    private final XMLInputFactory xml;

    XlsxReader() {
        this(Limits.DEFAULT);
    }

    XlsxReader(Limits limits) {
        this.limits = limits;
        this.xml = XMLInputFactory.newFactory();
        xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xml.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        trySet(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        trySet(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    }

    private void trySet(String property, Object value) {
        try {
            xml.setProperty(property, value);
        } catch (IllegalArgumentException e) {
            // Not every StAX implementation knows these; DTDs are refused below anyway.
        }
    }

    /** Every sheet of the workbook, in the order Excel shows them. */
    List<Sheet> read(byte[] file) {
        if (file == null || file.length == 0) {
            throw new XlsxException("The file is empty.");
        }
        if (file.length > limits.maxFileBytes()) {
            throw new XlsxException("The file is larger than " + limits.maxFileBytes() / (1024 * 1024) + " MB.");
        }
        if (file.length < 4 || file[0] != 'P' || file[1] != 'K' || file[2] != 3 || file[3] != 4) {
            throw new XlsxException("This is not an Excel (.xlsx) file. Save it as an Excel Workbook and try again.");
        }
        Map<String, byte[]> parts = unzip(file);

        byte[] workbookXml = parts.get("xl/workbook.xml");
        byte[] relsXml = parts.get("xl/_rels/workbook.xml.rels");
        if (workbookXml == null || relsXml == null) {
            throw new XlsxException("This is not an Excel (.xlsx) file. Save it as an Excel Workbook and try again.");
        }
        Map<String, String> targets = relationships(relsXml);
        WorkbookInfo workbook = workbook(workbookXml);
        if (workbook.sheets().size() > limits.maxSheets()) {
            throw new XlsxException("The file has more than " + limits.maxSheets() + " sheets.");
        }
        List<String> shared = parts.containsKey("xl/sharedStrings.xml") ? sharedStrings(parts.get("xl/sharedStrings.xml"))
                : List.of();
        Set<Integer> dateStyles = parts.containsKey("xl/styles.xml") ? dateStyles(parts.get("xl/styles.xml"))
                : Set.of();
        LocalDate epoch = workbook.date1904() ? EPOCH_1904 : EPOCH_1900;

        List<Sheet> sheets = new ArrayList<>();
        for (SheetRef ref : workbook.sheets()) {
            String target = targets.get(ref.relId());
            if (target == null) {
                throw new XlsxException("The sheet " + ref.name() + " is missing from the file.");
            }
            String path = resolve(target);
            byte[] sheetXml = parts.get(path);
            if (sheetXml == null) {
                throw new XlsxException("The sheet " + ref.name() + " is missing from the file.");
            }
            sheets.add(new Sheet(ref.name(), worksheet(ref.name(), sheetXml, shared, dateStyles, epoch)));
        }
        return sheets;
    }

    // ------------------------------------------------------------------ zip

    /** The parts the reader needs, each read with the size limits counted on the inflated bytes. */
    private Map<String, byte[]> unzip(byte[] file) {
        Map<String, byte[]> parts = new HashMap<>();
        Set<String> seen = new HashSet<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            ZipEntry entry;
            byte[] buffer = new byte[16 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > limits.maxEntries()) {
                    throw new XlsxException("The file has too many parts to be a spreadsheet.");
                }
                String name = entry.getName();
                if (!safeName(name)) {
                    throw new XlsxException("The file contains an unsafe path and was not read.");
                }
                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw new XlsxException("The file contains the same part twice and was not read.");
                }
                boolean wanted = !entry.isDirectory() && wanted(name);
                ByteArrayOutputStream out = wanted ? new ByteArrayOutputStream() : null;
                long size = 0;
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    size += n;
                    total += n;
                    if (size > limits.maxEntryBytes() || total > limits.maxTotalBytes()) {
                        throw new XlsxException("The file unpacks to far more data than a spreadsheet should. "
                                + "It was not read.");
                    }
                    if (out != null) {
                        out.write(buffer, 0, n);
                    }
                }
                if (out != null) {
                    parts.put(name, out.toByteArray());
                }
            }
        } catch (IOException e) {
            // ZipException, a truncated file (EOFException) and the like.
            throw damaged();
        }
        if (entries == 0) {
            throw new XlsxException("The file is damaged or is not an Excel (.xlsx) file.");
        }
        return parts;
    }

    static boolean safeName(String name) {
        if (name == null || name.isEmpty() || name.length() > 260) {
            return false;
        }
        if (name.startsWith("/") || name.contains("\\") || name.indexOf('\0') >= 0 || name.contains(":")) {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) {
                return false;
            }
        }
        return true;
    }

    private static boolean wanted(String name) {
        return name.equals("xl/workbook.xml") || name.equals("xl/_rels/workbook.xml.rels")
                || name.equals("xl/sharedStrings.xml") || name.equals("xl/styles.xml")
                || (name.startsWith("xl/worksheets/") && name.endsWith(".xml") && name.indexOf('/', 14) < 0);
    }

    /** A relationship target ("worksheets/sheet1.xml" or "/xl/worksheets/sheet1.xml") as a part name. */
    private static String resolve(String target) {
        String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
        if (!safeName(path) || !path.startsWith("xl/worksheets/")) {
            throw new XlsxException("The file contains an unsafe path and was not read.");
        }
        return path;
    }

    // ------------------------------------------------------------------ xml parts

    private record SheetRef(String name, String relId) {
    }

    private record WorkbookInfo(List<SheetRef> sheets, boolean date1904) {
    }

    private XMLStreamReader open(byte[] bytes) throws XMLStreamException {
        return xml.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }

    /** Moves to the next event, refusing any DTD. */
    private static int next(XMLStreamReader r) throws XMLStreamException {
        int event = r.next();
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE
                || event == XMLStreamConstants.ENTITY_DECLARATION) {
            throw new XlsxException("The file contains a document type declaration, which spreadsheets never need. "
                    + "It was not read.");
        }
        return event;
    }

    private WorkbookInfo workbook(byte[] bytes) {
        List<SheetRef> sheets = new ArrayList<>();
        boolean date1904 = false;
        try {
            XMLStreamReader r = open(bytes);
            try {
                while (r.hasNext()) {
                    if (next(r) != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    String local = r.getLocalName();
                    if (local.equals("workbookPr")) {
                        String value = r.getAttributeValue(null, "date1904");
                        date1904 = "1".equals(value) || "true".equalsIgnoreCase(value);
                    } else if (local.equals("sheet")) {
                        String name = r.getAttributeValue(null, "name");
                        String id = r.getAttributeValue(REL_NS, "id");
                        if (id == null) {
                            id = r.getAttributeValue(STRICT_REL_NS, "id");
                        }
                        if (name == null || id == null) {
                            throw new XlsxException("The list of sheets in the file is damaged.");
                        }
                        sheets.add(new SheetRef(name, id));
                        if (sheets.size() > limits.maxSheets()) {
                            throw new XlsxException("The file has more than " + limits.maxSheets() + " sheets.");
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw damaged();
        }
        return new WorkbookInfo(sheets, date1904);
    }

    private Map<String, String> relationships(byte[] bytes) {
        Map<String, String> targets = new HashMap<>();
        try {
            XMLStreamReader r = open(bytes);
            try {
                while (r.hasNext()) {
                    if (next(r) == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")) {
                        String id = r.getAttributeValue(null, "Id");
                        String target = r.getAttributeValue(null, "Target");
                        String mode = r.getAttributeValue(null, "TargetMode");
                        if (id != null && target != null && !"External".equalsIgnoreCase(mode)) {
                            targets.put(id, target);
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw damaged();
        }
        return targets;
    }

    private List<String> sharedStrings(byte[] bytes) {
        List<String> strings = new ArrayList<>();
        try {
            XMLStreamReader r = open(bytes);
            try {
                StringBuilder current = null;
                int phonetic = 0;
                boolean inText = false;
                while (r.hasNext()) {
                    int event = next(r);
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String local = r.getLocalName();
                        if (local.equals("si")) {
                            current = new StringBuilder();
                        } else if (local.equals("rPh")) {
                            phonetic++;
                        } else if (local.equals("t") && current != null && phonetic == 0) {
                            inText = true;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String local = r.getLocalName();
                        if (local.equals("si") && current != null) {
                            strings.add(current.toString());
                            if (strings.size() > limits.maxSharedStrings()) {
                                throw new XlsxException("The file has too much text to be read.");
                            }
                            current = null;
                        } else if (local.equals("rPh")) {
                            phonetic--;
                        } else if (local.equals("t")) {
                            inText = false;
                        }
                    } else if (inText && current != null && isText(event)) {
                        current.append(r.getText());
                        if (current.length() > limits.maxCellChars()) {
                            throw tooLong();
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw damaged();
        }
        return strings;
    }

    /** The cell formats (cellXfs indexes) whose number format shows a date. */
    private Set<Integer> dateStyles(byte[] bytes) {
        Map<Integer, String> customFormats = new HashMap<>();
        Set<Integer> dates = new HashSet<>();
        try {
            XMLStreamReader r = open(bytes);
            try {
                boolean inCellXfs = false;
                int xf = 0;
                while (r.hasNext()) {
                    int event = next(r);
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String local = r.getLocalName();
                        if (local.equals("numFmt")) {
                            Integer id = parseInt(r.getAttributeValue(null, "numFmtId"));
                            String code = r.getAttributeValue(null, "formatCode");
                            if (id != null && code != null) {
                                customFormats.put(id, code);
                            }
                        } else if (local.equals("cellXfs")) {
                            inCellXfs = true;
                        } else if (local.equals("xf") && inCellXfs) {
                            Integer id = parseInt(r.getAttributeValue(null, "numFmtId"));
                            if (id != null && isDateFormat(id, customFormats.get(id))) {
                                dates.add(xf);
                            }
                            xf++;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("cellXfs")) {
                        inCellXfs = false;
                    }
                }
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw damaged();
        }
        return dates;
    }

    static boolean isDateFormat(int id, String code) {
        if ((id >= 14 && id <= 17) || id == 22 || (id >= 27 && id <= 36) || (id >= 50 && id <= 58)) {
            return true;
        }
        if (code == null) {
            return false;
        }
        // Drop quoted text, escaped characters and [colour]/[locale] parts, then look for day or year codes.
        String bare = code.replaceAll("\"[^\"]*\"", "").replaceAll("\\\\.", "").replaceAll("\\[[^]]*]", "")
                .toLowerCase(Locale.ROOT);
        return bare.indexOf('d') >= 0 || bare.indexOf('y') >= 0;
    }

    private List<Row> worksheet(String sheetName, byte[] bytes, List<String> shared, Set<Integer> dateStyles,
            LocalDate epoch) {
        List<Row> rows = new ArrayList<>();
        try {
            XMLStreamReader r = open(bytes);
            try {
                int lastRow = 0;
                List<String> cells = null;
                int rowNumber = 0;
                // The cell being read.
                int column = -1;
                int nextColumn = 0;
                String type = null;
                Integer style = null;
                StringBuilder value = null;
                StringBuilder inline = null;
                boolean inValue = false;
                boolean inInlineText = false;
                int phonetic = 0;
                while (r.hasNext()) {
                    int event = next(r);
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String local = r.getLocalName();
                        switch (local) {
                            case "row" -> {
                                Integer number = parseInt(r.getAttributeValue(null, "r"));
                                rowNumber = number == null ? lastRow + 1 : number;
                                if (rowNumber <= lastRow || rowNumber > MAX_EXCEL_ROW) {
                                    throw new XlsxException("The rows of sheet " + sheetName + " are out of order.");
                                }
                                lastRow = rowNumber;
                                if (rows.size() >= limits.maxRowsPerSheet()) {
                                    throw new XlsxException("The sheet " + sheetName + " has more than "
                                            + String.format(Locale.ROOT, "%,d", limits.maxRowsPerSheet()) + " rows.");
                                }
                                cells = new ArrayList<>();
                                nextColumn = 0;
                            }
                            case "c" -> {
                                if (cells == null) {
                                    throw damaged();
                                }
                                String ref = r.getAttributeValue(null, "r");
                                column = ref == null ? nextColumn : columnIndex(ref);
                                if (column < 0) {
                                    throw damaged();
                                }
                                nextColumn = column + 1;
                                type = r.getAttributeValue(null, "t");
                                style = parseInt(r.getAttributeValue(null, "s"));
                                value = new StringBuilder();
                                inline = null;
                            }
                            case "v" -> inValue = value != null;
                            case "is" -> inline = new StringBuilder();
                            case "rPh" -> phonetic++;
                            case "t" -> inInlineText = inline != null && phonetic == 0;
                            default -> {
                                // formulas, styles and anything else are not needed
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String local = r.getLocalName();
                        switch (local) {
                            case "v" -> inValue = false;
                            case "t" -> inInlineText = false;
                            case "rPh" -> phonetic--;
                            case "c" -> {
                                if (column < limits.maxColumns()) {
                                    String text = cellText(type, style, value, inline, shared, dateStyles, epoch);
                                    if (!text.isEmpty()) {
                                        while (cells.size() <= column) {
                                            cells.add("");
                                        }
                                        cells.set(column, text);
                                    }
                                }
                                value = null;
                                inline = null;
                                column = -1;
                            }
                            case "row" -> {
                                rows.add(new Row(rowNumber, List.copyOf(cells)));
                                cells = null;
                            }
                            default -> {
                                // nothing to do
                            }
                        }
                    } else if (isText(event)) {
                        if (inValue && value != null) {
                            value.append(r.getText());
                            if (value.length() > limits.maxCellChars()) {
                                throw tooLong();
                            }
                        } else if (inInlineText && inline != null) {
                            inline.append(r.getText());
                            if (inline.length() > limits.maxCellChars()) {
                                throw tooLong();
                            }
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw damaged();
        }
        return rows;
    }

    private static String cellText(String type, Integer style, StringBuilder value, StringBuilder inline,
            List<String> shared, Set<Integer> dateStyles, LocalDate epoch) {
        String v = value == null ? "" : value.toString().trim();
        if ("inlineStr".equals(type)) {
            return inline == null ? "" : inline.toString();
        }
        if ("s".equals(type)) {
            Integer index = parseInt(v);
            if (index == null || index < 0 || index >= shared.size()) {
                throw new XlsxException("The file is damaged: a cell points to text that is not there.");
            }
            return shared.get(index);
        }
        if ("str".equals(type) || "e".equals(type)) {
            return "e".equals(type) ? "" : value == null ? "" : value.toString();
        }
        if ("b".equals(type)) {
            return "1".equals(v) ? "TRUE" : v.isEmpty() ? "" : "FALSE";
        }
        if (v.isEmpty()) {
            return "";
        }
        BigDecimal number;
        try {
            number = new BigDecimal(v);
        } catch (NumberFormatException e) {
            return v;
        }
        // A number written with an absurd exponent (1E999999999) stays as written: expanding it would take minutes
        // and gigabytes, or overflow. No column the import reads needs more than 30 digits.
        if (number.scale() > MAX_NUMBER_DIGITS || number.precision() - (long) number.scale() > MAX_NUMBER_DIGITS) {
            return v;
        }
        if (style != null && dateStyles.contains(style) && number.precision() - number.scale() <= 7) {
            long days = number.setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
            if (days >= 1 && days < 2_958_466) { // up to 9999-12-31
                return epoch.plusDays(days).toString();
            }
        }
        BigDecimal plain = number.stripTrailingZeros();
        return plain.scale() <= 0 ? plain.toBigInteger().toString() : plain.toPlainString();
    }

    /** The zero-based column of a reference such as "B12"; -1 when it is not one. */
    static int columnIndex(String ref) {
        int column = 0;
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            char c = Character.toUpperCase(ref.charAt(i));
            if (c < 'A' || c > 'Z' || i >= 3) {
                return -1;
            }
            column = column * 26 + (c - 'A' + 1);
            i++;
        }
        if (i == 0) {
            return -1;
        }
        for (int j = i; j < ref.length(); j++) {
            if (!Character.isDigit(ref.charAt(j))) {
                return -1;
            }
        }
        return column - 1;
    }

    private static boolean isText(int event) {
        return event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                || event == XMLStreamConstants.SPACE;
    }

    private static Integer parseInt(String text) {
        if (text == null || text.isEmpty() || text.length() > 9) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private XlsxException tooLong() {
        return new XlsxException("A cell in the file holds more than "
                + String.format(Locale.ROOT, "%,d", limits.maxCellChars()) + " characters.");
    }

    private static XlsxException damaged() {
        return new XlsxException("The file is damaged or is not an Excel (.xlsx) file.");
    }
}
