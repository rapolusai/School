import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { HostelOutingsReportView } from "@/components/views/reports/hostel-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Hostel outings report" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.hostelRead}>
      <HostelOutingsReportView />
    </RequirePermission>
  );
}
