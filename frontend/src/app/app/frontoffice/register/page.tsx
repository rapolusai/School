import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RegisterView } from "@/components/views/frontoffice/register-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Visitor register" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.frontofficeRead}>
      <RegisterView />
    </RequirePermission>
  );
}
