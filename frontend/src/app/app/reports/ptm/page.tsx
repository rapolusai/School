import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PtmAttendanceReportView } from "@/components/views/chat/ptm-report";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "PTM attendance" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.ptmManage}>
      <PtmAttendanceReportView />
    </RequirePermission>
  );
}
