import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { HOSTEL_PERMISSIONS } from "@/components/views/hostel/hostel-shared";
import { PassesView } from "@/components/views/hostel/passes-view";

export const metadata: Metadata = { title: "Hostel passes" };

export default function Page() {
  return (
    <RequireAny permissions={HOSTEL_PERMISSIONS}>
      <PassesView />
    </RequireAny>
  );
}
