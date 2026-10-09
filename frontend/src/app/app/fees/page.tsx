import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FeesOverviewView } from "@/components/views/fees/overview-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Fees" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.feesRead}>
      <FeesOverviewView />
    </RequirePermission>
  );
}
