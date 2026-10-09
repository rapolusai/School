import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StructuresView } from "@/components/views/fees/structures-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Fee structures" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.feesRead}>
      <StructuresView />
    </RequirePermission>
  );
}
