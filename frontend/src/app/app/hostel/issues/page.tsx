import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { HOSTEL_PERMISSIONS } from "@/components/views/hostel/hostel-shared";
import { IssuesView } from "@/components/views/hostel/issues-view";

export const metadata: Metadata = { title: "Hostel issues" };

export default function Page() {
  return (
    <RequireAny permissions={HOSTEL_PERMISSIONS}>
      <IssuesView />
    </RequireAny>
  );
}
