/** Types mirroring docs/api/phase-1.md (school setup and students). Keep in sync with the contract. */

/** A calendar date without time, "YYYY-MM-DD". */
export type PlainDate = string;

export type AcademicYear = {
  id: string;
  name: string;
  startsOn: PlainDate;
  endsOn: PlainDate;
  current: boolean;
};

export type YearRequest = { name: string; startsOn: PlainDate; endsOn: PlainDate; current?: boolean };

export type TeacherRef = { id: string; name: string };

export type SubjectRef = { id: string; name: string; code: string | null };

export type SectionView = {
  id: string;
  classId: string;
  className: string;
  name: string;
  capacity: number | null;
  classTeacher: TeacherRef | null;
  /** Students enrolled in the current academic year. */
  studentCount: number;
};

export type ClassView = {
  id: string;
  name: string;
  displayOrder: number;
  sections: SectionView[];
  subjects: SubjectRef[];
};

export type ClassRequest = { name: string; displayOrder?: number | null };

export type SectionRequest = { name: string; capacity?: number | null; classTeacherId?: string | null };

export type Subject = { id: string; name: string; code: string | null; classCount: number };

export type SubjectRequest = { name: string; code?: string | null };

export type SchoolProfile = {
  id: string;
  name: string;
  code: string;
  board: string;
  city: string | null;
  address: string | null;
  phone: string | null;
  contactEmail: string | null;
  udiseCode: string | null;
};

export type SchoolProfileRequest = {
  address: string;
  phone: string;
  contactEmail: string;
  udiseCode: string;
};

export const GENDERS = ["MALE", "FEMALE", "OTHER"] as const;
export type Gender = (typeof GENDERS)[number];

export const STUDENT_STATUSES = ["ACTIVE", "TRANSFERRED", "WITHDRAWN", "ALUMNI"] as const;
export type StudentStatus = (typeof STUDENT_STATUSES)[number];

export const GUARDIAN_RELATIONS = ["MOTHER", "FATHER", "GUARDIAN"] as const;
export type GuardianRelation = (typeof GUARDIAN_RELATIONS)[number];

export const BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"] as const;

export type StudentRow = {
  id: string;
  admissionNo: string;
  firstName: string;
  lastName: string | null;
  fullName: string;
  gender: Gender;
  dateOfBirth: PlainDate;
  status: StudentStatus;
  classId: string | null;
  className: string | null;
  sectionId: string | null;
  sectionName: string | null;
  rollNo: number | null;
  guardianName: string | null;
  guardianPhone: string | null;
};

export type StudentPage = {
  items: StudentRow[];
  /** 0-based. */
  page: number;
  size: number;
  total: number;
  /** The year the list is for; null when the school has no current year yet. */
  academicYearId: string | null;
};

export type StudentQuery = {
  yearId?: string;
  classId?: string;
  sectionId?: string;
  status?: StudentStatus;
  q?: string;
  page?: number;
  size?: number;
};

export type Guardian = {
  id: string;
  name: string;
  relation: GuardianRelation;
  /** Ten digits, without +91. */
  phone: string;
  email: string | null;
  occupation: string | null;
  primary: boolean;
  hasSignIn: boolean;
  signInEmail: string | null;
};

export type Sibling = {
  id: string;
  fullName: string;
  admissionNo: string;
  status: StudentStatus;
  className: string | null;
  sectionName: string | null;
};

export type Enrollment = {
  id: string;
  academicYearId: string;
  academicYearName: string;
  currentYear: boolean;
  classId: string;
  className: string;
  sectionId: string;
  sectionName: string;
  rollNo: number | null;
};

export type StudentDetail = {
  id: string;
  admissionNo: string;
  firstName: string;
  lastName: string | null;
  fullName: string;
  dateOfBirth: PlainDate;
  gender: Gender;
  admissionDate: PlainDate;
  status: StudentStatus;
  bloodGroup: string | null;
  address: string | null;
  previousSchool: string | null;
  apaarId: string | null;
  leftOn: PlainDate | null;
  leavingReason: string | null;
  hasSignIn: boolean;
  signInEmail: string | null;
  currentEnrollment: Enrollment | null;
  guardians: Guardian[];
  siblings: Sibling[];
  /** Newest year first. */
  enrollments: Enrollment[];
};

export type GuardianFields = {
  name: string;
  relation: GuardianRelation;
  phone: string;
  email?: string | null;
  occupation?: string | null;
  primary: boolean;
};

type StudentProfileFields = {
  admissionNo: string;
  firstName: string;
  lastName?: string | null;
  dateOfBirth: PlainDate;
  gender: Gender;
  admissionDate: PlainDate;
  bloodGroup?: string | null;
  address?: string | null;
  previousSchool?: string | null;
  apaarId?: string | null;
};

export type CreateStudentRequest = StudentProfileFields & {
  sectionId: string;
  rollNo?: number | null;
  guardians: GuardianFields[];
};

export type UpdateStudentRequest = StudentProfileFields & {
  sectionId?: string | null;
  rollNo?: number | null;
};

export type LeaveRequest = { status: "TRANSFERRED" | "WITHDRAWN"; leftOn: PlainDate; reason: string };

export type SignInRequest =
  | { mode: "CREATE"; email: string; password: string }
  | { mode: "LINK"; email: string };

export type PromoteRequest = {
  fromSectionId: string;
  fromYearId?: string | null;
  toSectionId?: string | null;
  toYearId?: string | null;
  graduate: boolean;
};

export type PromotionResult = {
  promoted: number;
  graduated: number;
  skipped: { studentId: string; fullName: string; reason: string }[];
};

export type ImportRowError = { row: number; column: string; message: string };

export type ImportResult = {
  dryRun: boolean;
  committed: boolean;
  totalRows: number;
  validRows: number;
  invalidRows: number;
  created: number;
  /** At most 500 problems; invalidRows counts every row with one. */
  errors: ImportRowError[];
  ignoredColumns: string[];
};

/** A student as their parent (or the student themself) sees them. */
export type Child = {
  id: string;
  fullName: string;
  admissionNo: string;
  status: StudentStatus;
  className: string | null;
  sectionName: string | null;
  rollNo: number | null;
  classTeacherName: string | null;
  academicYearName: string | null;
};
