import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { CourierView } from "@/components/views/frontoffice/courier-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Courier" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.frontofficeRead}>
      <CourierView />
    </RequirePermission>
  );
}
