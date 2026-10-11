import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { LESSONS_PERMISSIONS } from "@/components/views/lessons/lessons-shared";
import { MaterialListView } from "@/components/views/lessons/material-view";

export const metadata: Metadata = { title: "Study material" };

export default function Page() {
  return (
    <RequireAny permissions={LESSONS_PERMISSIONS}>
      <MaterialListView />
    </RequireAny>
  );
}
