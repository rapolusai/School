import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { CertificatesIssuedReportView } from "@/components/views/reports/certificate-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Certificates issued" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.certificatesRead}>
      <CertificatesIssuedReportView />
    </RequirePermission>
  );
}
