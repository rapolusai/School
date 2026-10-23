import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { InfirmaryView } from "@/components/views/health/infirmary-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Infirmary" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.healthRead}>
      <InfirmaryView />
    </RequirePermission>
  );
}
