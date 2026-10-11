import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { LibraryImportView } from "@/components/views/library/import-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Import books" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.libraryManage}>
      <LibraryImportView />
    </RequirePermission>
  );
}
