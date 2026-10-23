import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SchemesView } from "@/components/views/govreport/schemes-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Scholarships and schemes" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.govreportRead}>
      <SchemesView />
    </RequirePermission>
  );
}
