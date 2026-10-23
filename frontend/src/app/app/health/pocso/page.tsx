import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PocsoView } from "@/components/views/health/pocso-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "POCSO committee" };

/** Every member of staff may see who is on the committee; the complaints register is the committee's alone. */
export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.leaveRequest}>
      <PocsoView />
    </RequirePermission>
  );
}
