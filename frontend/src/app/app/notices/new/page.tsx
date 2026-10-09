import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ComposeView } from "@/components/views/communication/compose-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "New circular" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.noticesSend}>
      <ComposeView />
    </RequirePermission>
  );
}
