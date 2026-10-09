import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AttendanceReportsView } from "@/components/views/attendance/attendance-reports-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Attendance reports" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.attendanceRead}>
      <AttendanceReportsView />
    </RequirePermission>
  );
}
