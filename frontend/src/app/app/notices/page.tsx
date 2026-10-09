import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { NoticesView } from "@/components/views/communication/notices-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Circulars" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.noticesSend}>
      <NoticesView />
    </RequirePermission>
  );
}
