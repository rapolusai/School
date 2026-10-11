import type { Metadata } from "next";
import { ExamsHomeView } from "@/components/views/exams/exams-home";
import { EXAMS_PERMISSIONS, RequireAny } from "@/components/views/exams/exams-shared";

export const metadata: Metadata = { title: "Exams" };

/** Terms and exams of the year (exams.read); teachers with exams.manage alone get their marks entry list. */
export default function Page() {
  return (
    <RequireAny permissions={EXAMS_PERMISSIONS}>
      <ExamsHomeView />
    </RequireAny>
  );
}
