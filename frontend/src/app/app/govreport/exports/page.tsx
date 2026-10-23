import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ExportsView } from "@/components/views/govreport/exports-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Government exports" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.govreportRead}>
      <ExportsView />
    </RequirePermission>
  );
}
