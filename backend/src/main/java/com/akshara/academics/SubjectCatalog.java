package com.akshara.academics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.akshara.platform.Board;

/**
 * The usual subjects of each board, by class level, as a starting point a school reviews and changes. Levels are
 * {@link #NURSERY} (playgroup and nursery), {@link #LKG}, {@link #UKG} and 1 to 12. A subject marked optional is
 * offered but not ticked (a third language, say), since schools choose differently. Names are what a school would
 * write on a report card; the same name at several levels is one subject.
 */
final class SubjectCatalog {

    static final int NURSERY = -2;
    static final int LKG = -1;
    static final int UKG = 0;

    /** One subject of a board: taught from level {@code from} to {@code to}, both included. */
    record Entry(String name, String code, int from, int to, boolean optional) {

        boolean covers(int level) {
            return level >= from && level <= to;
        }
    }

    private static final Map<Board, List<Entry>> BY_BOARD = Map.of(
            Board.CBSE, List.of(
                    e("English", "ENG", NURSERY, 12),
                    e("Hindi", "HIN", NURSERY, 10),
                    e("Mathematics", "MAT", NURSERY, 12),
                    e("General Awareness", "GA", NURSERY, UKG),
                    e("Art and Craft", "ART", NURSERY, UKG),
                    e("Environmental Studies", "EVS", 1, 5),
                    e("Computer Science", "CS", 1, 12),
                    e("Art Education", "AE", 1, 8),
                    e("Health and Physical Education", "HPE", 1, 12),
                    e("Science", "SCI", 6, 10),
                    e("Social Science", "SST", 6, 10),
                    e("Sanskrit", "SKT", 6, 8),
                    opt("Telugu", "TEL", 6, 10),
                    opt("French", "FRE", 6, 10),
                    e("Information Technology", "IT", 9, 10),
                    e("Physics", "PHY", 11, 12),
                    e("Chemistry", "CHE", 11, 12),
                    e("Biology", "BIO", 11, 12),
                    opt("Accountancy", "ACC", 11, 12),
                    opt("Business Studies", "BST", 11, 12),
                    opt("Economics", "ECO", 11, 12)),
            Board.ICSE, List.of(
                    e("English", "ENG", NURSERY, 12),
                    e("Hindi", "HIN", NURSERY, 10),
                    e("Mathematics", "MAT", NURSERY, 12),
                    e("General Awareness", "GA", NURSERY, UKG),
                    e("Art and Craft", "ART", NURSERY, 8),
                    e("Environmental Studies", "EVS", 1, 5),
                    e("Computer Applications", "CA", 1, 10),
                    e("Physical Education", "PE", 1, 12),
                    e("Physics", "PHY", 6, 12),
                    e("Chemistry", "CHE", 6, 12),
                    e("Biology", "BIO", 6, 12),
                    e("History and Civics", "HC", 6, 10),
                    e("Geography", "GEO", 6, 10),
                    opt("Sanskrit", "SKT", 6, 8),
                    opt("Telugu", "TEL", 6, 10),
                    opt("French", "FRE", 6, 10),
                    opt("Computer Science", "CS", 11, 12),
                    opt("Accounts", "ACC", 11, 12),
                    opt("Commerce", "COM", 11, 12),
                    opt("Economics", "ECO", 11, 12)),
            // Telangana and Andhra Pradesh: Telugu first, Hindi second (from class 6), English third.
            Board.STATE, List.of(
                    e("Telugu", "TEL", NURSERY, 12),
                    e("English", "ENG", NURSERY, 12),
                    e("Mathematics", "MAT", NURSERY, 10),
                    e("General Awareness", "GA", NURSERY, UKG),
                    e("Art and Craft", "ART", NURSERY, 8),
                    e("Environmental Studies", "EVS", 1, 5),
                    e("Computer Education", "CE", 1, 10),
                    e("Physical Education", "PE", 1, 10),
                    e("Hindi", "HIN", 6, 10),
                    opt("Sanskrit", "SKT", 6, 12),
                    e("General Science", "GS", 6, 7),
                    e("Physical Science", "PS", 8, 10),
                    e("Biological Science", "BS", 8, 10),
                    e("Social Studies", "SS", 6, 10),
                    // Intermediate: MPC, BiPC and CEC groups. A school ticks the groups it runs.
                    opt("Mathematics A", "MATA", 11, 12),
                    opt("Mathematics B", "MATB", 11, 12),
                    opt("Physics", "PHY", 11, 12),
                    opt("Chemistry", "CHE", 11, 12),
                    opt("Botany", "BOT", 11, 12),
                    opt("Zoology", "ZOO", 11, 12),
                    opt("Civics", "CIV", 11, 12),
                    opt("Economics", "ECO", 11, 12),
                    opt("Commerce", "COM", 11, 12)),
            // Primary Years (to 5), Middle Years (6 to 10) and the Diploma (11, 12).
            Board.IB, List.of(
                    e("English", "ENG", NURSERY, 5),
                    e("Mathematics", "MAT", NURSERY, 12),
                    e("Science", "SCI", 1, 5),
                    e("Social Studies", "SOC", 1, 5),
                    e("Arts", "ART", NURSERY, 12),
                    e("Personal, Social and Physical Education", "PSPE", NURSERY, 5),
                    opt("Additional Language", "ADL", 1, 5),
                    e("Language and Literature", "LL", 6, 12),
                    e("Language Acquisition", "LA", 6, 12),
                    e("Individuals and Societies", "IS", 6, 12),
                    e("Sciences", "SCS", 6, 12),
                    e("Physical and Health Education", "PHE", 6, 10),
                    e("Design", "DES", 6, 10),
                    e("Theory of Knowledge", "TOK", 11, 12),
                    e("Creativity, Activity, Service", "CAS", 11, 12)),
            // Primary (to 6), Lower Secondary (7, 8), IGCSE (9, 10) and AS and A Level (11, 12).
            Board.CAMBRIDGE, List.of(
                    e("English", "ENG", NURSERY, 12),
                    e("Mathematics", "MAT", NURSERY, 12),
                    e("General Awareness", "GA", NURSERY, UKG),
                    e("Art and Design", "ART", NURSERY, 8),
                    e("Science", "SCI", 1, 8),
                    e("Computing", "CMP", 1, 8),
                    e("Global Perspectives", "GP", 1, 10),
                    e("Physical Education", "PE", 1, 10),
                    opt("Hindi", "HIN", 1, 10),
                    opt("Music", "MUS", 1, 8),
                    e("History", "HIS", 7, 10),
                    e("Geography", "GEO", 7, 10),
                    e("Physics", "PHY", 9, 12),
                    e("Chemistry", "CHE", 9, 12),
                    e("Biology", "BIO", 9, 12),
                    e("Computer Science", "CS", 9, 12),
                    opt("Economics", "ECO", 9, 12),
                    opt("Business", "BUS", 9, 12),
                    opt("Accounting", "ACC", 11, 12)));

    private static final Pattern LEADING_WORD = Pattern.compile(
            "^(class|grade|std|standard|stage|year|form)\\s*\\.?\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("^(\\d{1,2})(st|nd|rd|th)?$");
    private static final Pattern ROMAN = Pattern.compile("^(xii|xi|x|ix|viii|vii|vi|v|iv|iii|ii|i)$");
    private static final List<String> ROMANS = List.of("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x",
            "xi", "xii");

    private SubjectCatalog() {
    }

    private static Entry e(String name, String code, int from, int to) {
        return new Entry(name, code, from, to, false);
    }

    private static Entry opt(String name, String code, int from, int to) {
        return new Entry(name, code, from, to, true);
    }

    /** Every subject the board's lists name, in catalogue order. */
    static List<Entry> entries(Board board) {
        return BY_BOARD.getOrDefault(board, List.of());
    }

    /** The subjects of a level, in catalogue order. */
    static List<Entry> forLevel(Board board, int level) {
        List<Entry> found = new ArrayList<>();
        for (Entry entry : entries(board)) {
            if (entry.covers(level)) {
                found.add(entry);
            }
        }
        return found;
    }

    /**
     * The level a class name stands for: "LKG", "PP1", "Nursery", "Class 5", "5", "Grade V", "Std 10th", "XII". Null
     * when the name does not say (a school's own names such as "Rose"), so that class gets no suggestions.
     */
    static Integer levelOf(String className) {
        if (className == null) {
            return null;
        }
        String name = className.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s._-]+", " ");
        String compact = name.replace(" ", "");
        switch (compact) {
            case "nursery", "playgroup", "playschool", "prenursery", "prekg", "pkg", "toddlers" -> {
                return NURSERY;
            }
            case "lkg", "lowerkg", "kg1", "pp1", "preprimary1", "jrkg", "juniorkg" -> {
                return LKG;
            }
            case "ukg", "upperkg", "kg2", "pp2", "preprimary2", "srkg", "seniorkg", "kg" -> {
                return UKG;
            }
            default -> {
                // Not a pre-primary name: look for a class number below.
            }
        }
        String rest = LEADING_WORD.matcher(name).replaceFirst("").trim();
        Matcher number = NUMBER.matcher(rest);
        if (number.matches()) {
            int level = Integer.parseInt(number.group(1));
            return level >= 1 && level <= 12 ? level : null;
        }
        if (ROMAN.matcher(rest).matches()) {
            return ROMANS.indexOf(rest) + 1;
        }
        return null;
    }
}
