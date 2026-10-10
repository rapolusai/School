import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { NoticeAdminView } from "@/components/views/privacy/notice-admin-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Privacy notice" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.privacyManage}>
      <NoticeAdminView />
    </RequirePermission>
  );
}
