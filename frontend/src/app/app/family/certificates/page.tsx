import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyCertificatesView } from "@/components/views/certificates/family-certificates";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Certificates" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.dashboardView}>
      <FamilyCertificatesView />
    </RequirePermission>
  );
}
