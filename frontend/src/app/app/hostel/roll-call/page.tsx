import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { HOSTEL_PERMISSIONS } from "@/components/views/hostel/hostel-shared";
import { RollCallView } from "@/components/views/hostel/roll-call-view";

export const metadata: Metadata = { title: "Night roll call" };

export default function Page() {
  return (
    <RequireAny permissions={HOSTEL_PERMISSIONS}>
      <RollCallView />
    </RequireAny>
  );
}
