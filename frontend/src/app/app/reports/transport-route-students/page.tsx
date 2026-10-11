import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { TransportRouteStudentsReportView } from "@/components/views/reports/transport-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Students by route" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.transportRead}>
      <TransportRouteStudentsReportView />
    </RequirePermission>
  );
}
