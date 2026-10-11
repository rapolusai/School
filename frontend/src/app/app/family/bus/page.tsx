import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyBusView } from "@/components/views/portal/family-bus";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Bus" };

/** The parent and student app: each child's school bus, today's trips and where the bus is. */
export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.dashboardView}>
      <FamilyBusView />
    </RequirePermission>
  );
}
