import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FinesView } from "@/components/views/library/fines-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Library fines" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.libraryManage}>
      <FinesView />
    </RequirePermission>
  );
}
