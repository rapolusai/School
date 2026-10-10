import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AbsenteesReportView } from "@/components/views/reports/absentees-report";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Daily absentees" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.attendanceRead}>
      <AbsenteesReportView />
    </RequirePermission>
  );
}
