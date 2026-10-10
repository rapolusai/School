import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ConsentsView } from "@/components/views/privacy/consents-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Consent" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.privacyManage}>
      <ConsentsView />
    </RequirePermission>
  );
}
