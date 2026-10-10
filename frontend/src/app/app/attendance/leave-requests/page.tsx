import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { LeaveInboxView } from "@/components/views/portal/leave-inbox";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Leave requests" };

/** Class teachers (their sections) and attendance.manage (every section) decide child leave requests. */
export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.attendanceRead}>
      <LeaveInboxView />
    </RequirePermission>
  );
}
