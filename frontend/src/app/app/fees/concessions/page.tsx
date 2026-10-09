import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ConcessionsView } from "@/components/views/fees/concessions-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Concessions" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.feesRead}>
      <ConcessionsView />
    </RequirePermission>
  );
}
