import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { LESSONS_PERMISSIONS } from "@/components/views/lessons/lessons-shared";
import { SyllabusEditorView } from "@/components/views/lessons/syllabus-editor";

export const metadata: Metadata = { title: "Syllabus" };

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** One syllabus; "new" starts one for ?class=…&subject=… (and ?year=…). */
export default async function Page({ params, searchParams }: PageProps<"/app/lessons/syllabus/[syllabusId]">) {
  const { syllabusId } = await params;
  const query = await searchParams;
  return (
    <RequireAny permissions={LESSONS_PERMISSIONS}>
      <SyllabusEditorView
        key={syllabusId}
        syllabusId={syllabusId}
        yearId={first(query.year)}
        classId={first(query.class)}
        subjectId={first(query.subject)}
      />
    </RequireAny>
  );
}
