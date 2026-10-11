import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RoutesView } from "@/components/views/transport/routes-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Transport" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.transportRead}>
      <RoutesView />
    </RequirePermission>
  );
}
