import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SchoolsView } from "@/components/views/schools-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Schools" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.platformAdmin}>
      <SchoolsView />
    </RequirePermission>
  );
}
