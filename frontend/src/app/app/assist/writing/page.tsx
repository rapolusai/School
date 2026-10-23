import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { WritingView } from "@/components/views/assist/writing-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Writing help" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.assistUse}>
      <WritingView />
    </RequirePermission>
  );
}
