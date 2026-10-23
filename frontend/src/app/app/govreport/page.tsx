import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ProfileView } from "@/components/views/govreport/profile-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Government reporting" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.govreportRead}>
      <ProfileView />
    </RequirePermission>
  );
}
