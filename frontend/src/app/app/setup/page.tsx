import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SetupView } from "@/components/views/setup/setup-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "School setup" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.academicsRead}>
      <SetupView />
    </RequirePermission>
  );
}
