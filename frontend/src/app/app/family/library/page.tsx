import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyLibraryView } from "@/components/views/portal/family-library";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Library" };

/** The parent and student app: books on loan from the school library, due dates and fines. */
export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.dashboardView}>
      <FamilyLibraryView />
    </RequirePermission>
  );
}
