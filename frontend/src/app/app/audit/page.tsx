import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AuditView } from "@/components/views/audit-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Audit trail" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.auditRead}>
      <AuditView />
    </RequirePermission>
  );
}
