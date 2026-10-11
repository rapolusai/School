import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { TransportBoardingReportView } from "@/components/views/reports/transport-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Boarding log" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.transportRead}>
      <TransportBoardingReportView />
    </RequirePermission>
  );
}
