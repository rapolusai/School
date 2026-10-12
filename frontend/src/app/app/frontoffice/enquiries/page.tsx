import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { EnquiriesView } from "@/components/views/frontoffice/enquiries-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Enquiries" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.frontofficeRead}>
      <EnquiriesView />
    </RequirePermission>
  );
}
