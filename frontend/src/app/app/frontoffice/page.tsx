import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { VisitorsView } from "@/components/views/frontoffice/visitors-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Visitors" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.frontofficeRead}>
      <VisitorsView />
    </RequirePermission>
  );
}
