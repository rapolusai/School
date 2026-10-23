import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { HOSTEL_PERMISSIONS } from "@/components/views/hostel/hostel-shared";
import { OccupancyView } from "@/components/views/hostel/occupancy-view";

export const metadata: Metadata = { title: "Hostel occupancy" };

export default function Page() {
  return (
    <RequireAny permissions={HOSTEL_PERMISSIONS}>
      <OccupancyView />
    </RequireAny>
  );
}
