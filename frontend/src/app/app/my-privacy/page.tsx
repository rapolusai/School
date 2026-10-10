import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { MyPrivacyView } from "@/components/views/privacy/my-privacy-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Privacy" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.childView}>
      <MyPrivacyView />
    </RequirePermission>
  );
}
