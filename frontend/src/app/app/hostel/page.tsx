import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { HOSTEL_PERMISSIONS } from "@/components/views/hostel/hostel-shared";
import { HostelsView } from "@/components/views/hostel/hostels-view";

export const metadata: Metadata = { title: "Hostel" };

export default function Page() {
  return (
    <RequireAny permissions={HOSTEL_PERMISSIONS}>
      <HostelsView />
    </RequireAny>
  );
}
