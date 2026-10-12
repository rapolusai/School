import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { CertificatesPendingReportView } from "@/components/views/reports/certificate-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Certificates pending" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.certificatesRead}>
      <CertificatesPendingReportView />
    </RequirePermission>
  );
}
