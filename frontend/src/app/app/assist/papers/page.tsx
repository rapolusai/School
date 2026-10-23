import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PapersView } from "@/components/views/assist/papers-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Draft papers" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.assistUse}>
      <PapersView />
    </RequirePermission>
  );
}
