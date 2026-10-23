import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StaffChecksView } from "@/components/views/health/staff-checks-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Staff verification" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.safetyManage}>
      <StaffChecksView />
    </RequirePermission>
  );
}
