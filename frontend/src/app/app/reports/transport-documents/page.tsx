import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { TransportDocumentsReportView } from "@/components/views/reports/transport-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Vehicle documents" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.transportRead}>
      <TransportDocumentsReportView />
    </RequirePermission>
  );
}
