import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { GateView } from "@/components/views/frontoffice/gate-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Gate" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.gatePass}>
      <GateView />
    </RequirePermission>
  );
}
