import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SectionsReportView } from "@/components/views/reports/sections-report";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Attendance by class and section" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.attendanceRead}>
      <SectionsReportView />
    </RequirePermission>
  );
}
