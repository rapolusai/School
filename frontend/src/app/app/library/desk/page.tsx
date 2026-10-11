import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { DeskView } from "@/components/views/library/desk-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Circulation desk" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.libraryManage}>
      <DeskView />
    </RequirePermission>
  );
}
