import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RolesView } from "@/components/views/roles-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Roles & permissions" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.rolesRead}>
      <RolesView />
    </RequirePermission>
  );
}
