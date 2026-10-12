import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { CertificatesView } from "@/components/views/certificates/certificates-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Certificates" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.certificatesRead}>
      <CertificatesView />
    </RequirePermission>
  );
}
