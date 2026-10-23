import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { HOSTEL_PERMISSIONS } from "@/components/views/hostel/hostel-shared";
import { MessView } from "@/components/views/hostel/mess-view";

export const metadata: Metadata = { title: "Mess" };

export default function Page() {
  return (
    <RequireAny permissions={HOSTEL_PERMISSIONS}>
      <MessView />
    </RequireAny>
  );
}
