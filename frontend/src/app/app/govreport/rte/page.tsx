import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RteIntakesView } from "@/components/views/govreport/rte-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "RTE 25% admissions" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.govreportRead}>
      <RteIntakesView />
    </RequirePermission>
  );
}
