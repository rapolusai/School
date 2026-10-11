import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { LibraryPopularReportView } from "@/components/views/reports/library-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Most borrowed books" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.libraryManage}>
      <LibraryPopularReportView />
    </RequirePermission>
  );
}
