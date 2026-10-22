import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { IdCardsPendingReportView } from "@/components/views/reports/certificate-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "ID cards pending" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.certificatesRead}>
      <IdCardsPendingReportView />
    </RequirePermission>
  );
}
