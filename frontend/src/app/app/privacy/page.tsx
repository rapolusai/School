import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RequestsView } from "@/components/views/privacy/requests-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Data requests" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.privacyManage}>
      <RequestsView />
    </RequirePermission>
  );
}
