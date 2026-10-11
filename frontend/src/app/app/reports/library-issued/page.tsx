import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { LibraryIssuedReportView } from "@/components/views/reports/library-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Books issued" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.libraryManage}>
      <LibraryIssuedReportView />
    </RequirePermission>
  );
}
