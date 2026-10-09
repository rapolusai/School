import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StaffView } from "@/components/views/staff/staff-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Staff" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.staffRead}>
      <StaffView />
    </RequirePermission>
  );
}
