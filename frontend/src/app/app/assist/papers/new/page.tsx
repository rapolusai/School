import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PaperBuilderView } from "@/components/views/assist/papers-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "New draft paper" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.assistUse}>
      <PaperBuilderView />
    </RequirePermission>
  );
}
