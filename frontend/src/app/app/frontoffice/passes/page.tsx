import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PassesView } from "@/components/views/frontoffice/passes-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Gate passes" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.frontofficeRead}>
      <PassesView />
    </RequirePermission>
  );
}
