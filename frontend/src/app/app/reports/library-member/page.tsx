import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { LibraryMemberHistoryView } from "@/components/views/reports/library-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Member history" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.libraryManage}>
      <LibraryMemberHistoryView />
    </RequirePermission>
  );
}
