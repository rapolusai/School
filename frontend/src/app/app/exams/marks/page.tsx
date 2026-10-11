import type { Metadata } from "next";
import { MARKS_PERMISSIONS, RequireAny } from "@/components/views/exams/exams-shared";
import { MarksOverviewView } from "@/components/views/exams/marks-overview";

export const metadata: Metadata = { title: "Marks entry" };

export default function Page() {
  return (
    <RequireAny permissions={MARKS_PERMISSIONS}>
      <MarksOverviewView />
    </RequireAny>
  );
}
