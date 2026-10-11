import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ApprovalsView } from "@/components/views/lessons/plans-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Plans to approve" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.lessonsApprove}>
      <ApprovalsView />
    </RequirePermission>
  );
}
