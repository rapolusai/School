import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { LibrarySettingsView } from "@/components/views/library/settings-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Library settings" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.libraryManage}>
      <LibrarySettingsView />
    </RequirePermission>
  );
}
