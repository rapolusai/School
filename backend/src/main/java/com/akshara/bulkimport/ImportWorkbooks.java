package com.akshara.bulkimport;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.reports.Xlsx;
import com.akshara.shared.TenantContext;

/**
 * The workbooks the school downloads: the template (an Instructions sheet, a Staff sheet and one empty sheet per class
 * and section the school has set up) and a sample filled with made-up students and staff to try the import with.
 * Everything in the sample is invented: names from common lists, mobiles in one made-up range, emails at
 * example.com (a domain reserved for examples). Nothing is ever sent to these numbers or addresses.
 */
@Component
public class ImportWorkbooks {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** The sample: 10 classes x 2 sections x 100 students, and 30 staff. */
    static final int SAMPLE_CLASSES = 10;
    static final List<String> SAMPLE_SECTIONS = List.of("A", "B");
    static final int SAMPLE_PER_SECTION = 100;

    private final AcademicsDirectory directory;

    public ImportWorkbooks(AcademicsDirectory directory) {
        this.directory = directory;
    }

    /** The template for this school: a sheet for every section it has set up, or two example sheets if none. */
    @Transactional(readOnly = true)
    public byte[] template() {
        TenantContext.require();
        List<Xlsx.Sheet> sheets = new ArrayList<>();
        sheets.add(instructions());
        sheets.add(staffSheet());
        List<SectionInfo> sections = directory.sections();
        if (sections.isEmpty()) {
            sheets.add(studentSheet("Class 1 - A"));
            sheets.add(studentSheet("Class 1 - B"));
        } else {
            for (SectionInfo s : sections) {
                sheets.add(studentSheet(s.className() + " - " + s.name()));
            }
        }
        return Xlsx.write(sheets);
    }

    /** About 2,000 made-up students in Classes 1 to 10 (sections A and B) and 30 made-up staff. */
    public byte[] sample() {
        LocalDate today = LocalDate.now(INDIA);
        // The academic year that started most recently (in June).
        int yearStart = today.getMonthValue() >= 6 ? today.getYear() : today.getYear() - 1;
        List<Xlsx.Sheet> sheets = new ArrayList<>();
        sheets.add(instructions());
        sheets.add(sampleStaff(yearStart));
        int n = 0;
        for (int grade = 1; grade <= SAMPLE_CLASSES; grade++) {
            for (String section : SAMPLE_SECTIONS) {
                Xlsx.Sheet sheet = studentSheet("Class " + grade + " - " + section);
                for (int roll = 1; roll <= SAMPLE_PER_SECTION; roll++) {
                    n++;
                    sheet.row(sampleStudent(n, grade, roll, yearStart));
                }
                sheets.add(sheet);
            }
        }
        return Xlsx.write(sheets);
    }

    // ------------------------------------------------------------------ sheets

    static Xlsx.Sheet instructions() {
        Xlsx.Sheet s = new Xlsx.Sheet(SchoolImportService.INSTRUCTIONS);
        s.title("Bring your students and staff into Akshara");
        s.blank();
        s.line("1. Put your staff on the Staff sheet, one person per row.");
        s.line("2. Put each class and section on its own sheet, named like \"Class 5 - A\" (class, hyphen, section).");
        s.line("   Add a sheet for a section by copying one and renaming it. Leave a sheet empty to skip it.");
        s.line("3. Keep the headings in the first row of each sheet. Columns can be in any order; extra columns "
                + "are ignored.");
        s.line("4. Upload the file in School setup. It is checked first: every problem is listed by sheet and row, "
                + "and nothing is saved until the whole file is correct. Then everything is saved at once.");
        s.blank();
        s.header("Students column", "Required", "How to fill it");
        s.row("Admission no", "Yes", "Up to 30 letters, digits and / . _ -. Must be unique in the school.");
        s.row("Roll no", "No", "1 to 999, unique in the section.");
        s.row("First name", "Yes", "Up to 100 characters.");
        s.row("Last name", "No", "Up to 100 characters.");
        s.row("Date of birth", "Yes", "A date: YYYY-MM-DD, DD-MM-YYYY or DD/MM/YYYY, or an Excel date.");
        s.row("Gender", "Yes", "MALE, FEMALE or OTHER (or M, F, O).");
        s.row("Guardian name", "Yes", "The parent or guardian. Children with the same guardian name and mobile "
                + "are linked as siblings.");
        s.row("Relation", "Yes", "FATHER, MOTHER or GUARDIAN.");
        s.row("Guardian mobile", "Yes", "A 10-digit Indian mobile number, with or without +91.");
        s.row("Guardian email", "No", "Leave empty if not known.");
        s.row("Admission date", "No", "The day the student joined. Empty means the day of the import.");
        s.blank();
        s.header("Staff column", "Required", "How to fill it");
        s.row("Name", "Yes", "Up to 200 characters.");
        s.row("Email", "Yes", "The email they will sign in with. Must be unique in the school.");
        s.row("Mobile", "Yes", "A 10-digit Indian mobile number.");
        s.row("Role", "Yes", "Teacher, Principal, Accountant, Front office, Librarian, Nurse, ... "
                + "Separate two roles with a comma.");
        s.row("Designation", "Yes", "For example PGT Maths or Office assistant.");
        s.row("Department", "No", "For example Science. Missing departments can be added by the import.");
        s.row("Employment type", "Yes", "PERMANENT, CONTRACT, PART_TIME or PROBATION.");
        s.row("Joining date", "Yes", "A date, as above.");
        s.row("Employee code", "Yes", "Up to 30 letters, digits and / . _ -. Must be unique in the school.");
        s.blank();
        s.line("Sign-ins: staff accounts are created without a password, so nobody can sign in with them yet. "
                + "The School Admin sets each person's password from their staff page.");
        s.line("Parents get no sign-in from the import. Give a parent a sign-in from their child's page.");
        s.line("Do not add Aadhaar numbers: they are not needed and are never saved.");
        s.line("A file can hold up to 5,000 students and 500 staff, and be at most 5 MB.");
        return s;
    }

    static Xlsx.Sheet studentSheet(String name) {
        Xlsx.Sheet sheet = new Xlsx.Sheet(name);
        sheet.header(ImportColumns.STUDENT.stream().map(ImportColumns.Column::heading).toArray(String[]::new));
        return sheet;
    }

    static Xlsx.Sheet staffSheet() {
        Xlsx.Sheet sheet = new Xlsx.Sheet(SchoolImportService.STAFF);
        sheet.header(ImportColumns.STAFF.stream().map(ImportColumns.Column::heading).toArray(String[]::new));
        return sheet;
    }

    // ------------------------------------------------------------------ the sample

    private static final String[] BOYS = {"Aarav", "Vivaan", "Aditya", "Arjun", "Sai", "Reyansh", "Krishna",
        "Ishaan", "Rohan", "Kabir", "Ayaan", "Dhruv", "Harsh", "Karthik", "Manav", "Nikhil", "Pranav", "Rahul",
        "Siddharth", "Varun", "Yash", "Anirudh", "Gautam", "Rithvik", "Tejas"};
    private static final String[] GIRLS = {"Aanya", "Diya", "Saanvi", "Ananya", "Aadhya", "Pari", "Kavya",
        "Ishita", "Myra", "Sara", "Meera", "Riya", "Lakshmi", "Nandini", "Pooja", "Sneha", "Tanvi", "Vaishnavi",
        "Keerthi", "Harini", "Bhavya", "Charvi", "Divya", "Gayatri", "Sahana"};
    private static final String[] SURNAMES = {"Sharma", "Reddy", "Rao", "Iyer", "Patel", "Nair", "Gupta",
        "Kumar", "Singh", "Verma", "Naidu", "Menon", "Joshi", "Kulkarni", "Desai", "Chowdary", "Pillai", "Das",
        "Mishra", "Yadav", "Shetty", "Bhat", "Varma", "Agarwal", "Khan"};
    private static final String[] FATHERS = {"Ramesh", "Suresh", "Mahesh", "Srinivas", "Venkat", "Rajesh",
        "Prakash", "Anil", "Sunil", "Vijay", "Ravi", "Sanjay", "Imran", "Kiran", "Mohan"};
    private static final String[] MOTHERS = {"Lakshmi", "Padma", "Sunita", "Anitha", "Kavitha", "Radha",
        "Swathi", "Rekha", "Geetha", "Shabana", "Usha", "Jyothi", "Latha", "Priya", "Deepa"};

    /** One made-up student: admission no, roll no, names, birth date, gender, guardian, mobile, email, admission. */
    static List<Object> sampleStudent(int n, int grade, int roll, int yearStart) {
        boolean girl = n % 2 == 0;
        String first = girl ? GIRLS[(n / 2) % GIRLS.length] : BOYS[(n / 2) % BOYS.length];
        // Some children in Classes 1, 2, 5 and 6 are younger siblings of the child with the same section and roll
        // number two classes up: same guardian name and mobile, so they are linked as siblings.
        boolean younger = n % 20 == 0 && (grade <= 2 || grade == 5 || grade == 6);
        int family = younger ? n + 2 * SAMPLE_SECTIONS.size() * SAMPLE_PER_SECTION : n;
        String surname = SURNAMES[(family * 7) % SURNAMES.length];
        boolean father = family % 3 != 0;
        String guardian = (father ? FATHERS[family % FATHERS.length] : MOTHERS[family % MOTHERS.length]) + " "
                + surname;
        String mobile = "70000" + String.format(Locale.ROOT, "%05d", family);
        LocalDate born = LocalDate.of(yearStart - 5 - grade, 1 + (n * 5) % 12, 1 + (n * 11) % 28);
        // Most joined in Class 1 or later, in June.
        int joinedYearsAgo = Math.min(grade - 1, n % 4);
        LocalDate admitted = LocalDate.of(yearStart - joinedYearsAgo, 6, 2);
        String email = family % 5 == 0 ? "parent" + family + "@example.com" : null;
        List<Object> row = new ArrayList<>();
        row.add(String.format(Locale.ROOT, "SMP/%04d", n));
        row.add(roll);
        row.add(first);
        row.add(surname);
        row.add(born);
        row.add(girl ? "FEMALE" : "MALE");
        row.add(guardian);
        row.add(father ? "FATHER" : "MOTHER");
        row.add(mobile);
        row.add(email);
        row.add(admitted);
        return row;
    }

    private static final String[][] STAFF = {
        {"Principal", "Principal", "Administration", "PERMANENT"},
        {"Teacher", "PGT Mathematics", "Mathematics", "PERMANENT"},
        {"Teacher", "TGT Mathematics", "Mathematics", "PERMANENT"},
        {"Teacher", "PRT Mathematics", "Mathematics", "CONTRACT"},
        {"Teacher", "PGT Physics", "Science", "PERMANENT"},
        {"Teacher", "PGT Chemistry", "Science", "PERMANENT"},
        {"Teacher", "TGT Biology", "Science", "PERMANENT"},
        {"Teacher", "TGT Science", "Science", "PROBATION"},
        {"Teacher", "PRT EVS", "Science", "PERMANENT"},
        {"Teacher", "PGT English", "Languages", "PERMANENT"},
        {"Teacher", "TGT English", "Languages", "PERMANENT"},
        {"Teacher", "TGT Hindi", "Languages", "PERMANENT"},
        {"Teacher", "TGT Telugu", "Languages", "CONTRACT"},
        {"Teacher", "PRT English", "Languages", "PERMANENT"},
        {"Teacher", "TGT Social Studies", "Social Studies", "PERMANENT"},
        {"Teacher", "TGT History", "Social Studies", "PERMANENT"},
        {"Teacher", "PRT General", "Primary", "PERMANENT"},
        {"Teacher", "PRT General", "Primary", "PERMANENT"},
        {"Teacher", "PRT General", "Primary", "PROBATION"},
        {"Teacher", "PRT General", "Primary", "PERMANENT"},
        {"Teacher", "Computer Teacher", "Computers", "CONTRACT"},
        {"Teacher", "Physical Education Teacher", "Sports", "PERMANENT"},
        {"Teacher", "Art Teacher", "Arts", "PART_TIME"},
        {"Teacher, Librarian", "Librarian", "Administration", "PERMANENT"},
        {"Accountant", "Accountant", "Administration", "PERMANENT"},
        {"Accountant", "Accounts Assistant", "Administration", "CONTRACT"},
        {"Front office", "Receptionist", "Administration", "PERMANENT"},
        {"Front office", "Office Assistant", "Administration", "CONTRACT"},
        {"Nurse", "School Nurse", "Administration", "PERMANENT"},
        {"Security", "Security Guard", "Administration", "CONTRACT"},
    };

    static Xlsx.Sheet sampleStaff(int yearStart) {
        Xlsx.Sheet sheet = staffSheet();
        for (int i = 0; i < STAFF.length; i++) {
            String[] s = STAFF[i];
            boolean woman = i % 2 == 1;
            String first = woman ? GIRLS[(i * 3) % GIRLS.length] : BOYS[(i * 3) % BOYS.length];
            String surname = SURNAMES[(i * 11 + 3) % SURNAMES.length];
            String email = (first + "." + surname).toLowerCase(Locale.ROOT) + (i + 1) + "@example.com";
            sheet.row(first + " " + surname, email, "70001" + String.format(Locale.ROOT, "%05d", i + 1), s[0], s[1],
                    s[2], s[3], LocalDate.of(yearStart - (i % 12), 6, 1), String.format(Locale.ROOT, "SMP-E%03d", i + 1));
        }
        return sheet;
    }
}
