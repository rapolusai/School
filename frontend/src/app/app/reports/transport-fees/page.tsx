import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { TransportFeesReportView } from "@/components/views/reports/transport-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Transport fees by route" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.transportRead}>
      <TransportFeesReportView />
    </RequirePermission>
  );
}
