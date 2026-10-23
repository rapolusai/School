import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { CodeListsView } from "@/components/views/govreport/code-lists-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Government code lists" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.govreportRead}>
      <CodeListsView />
    </RequirePermission>
  );
}
