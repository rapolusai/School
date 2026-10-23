import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SafetyView } from "@/components/views/health/safety-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Safety registers" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.safetyManage}>
      <SafetyView />
    </RequirePermission>
  );
}
