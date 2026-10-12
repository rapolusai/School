import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { BlocklistView } from "@/components/views/frontoffice/blocklist-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Blocklist" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.frontofficeRead}>
      <BlocklistView />
    </RequirePermission>
  );
}
