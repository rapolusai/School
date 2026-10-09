import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { HealthView } from "@/components/views/billing/health-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Platform health" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.platformAdmin}>
      <HealthView />
    </RequirePermission>
  );
}
