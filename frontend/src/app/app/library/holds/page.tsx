import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { HoldsView } from "@/components/views/library/holds-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Holds" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.libraryManage}>
      <HoldsView />
    </RequirePermission>
  );
}
