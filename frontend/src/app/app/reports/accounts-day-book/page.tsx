import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { DayBookReport } from "@/components/views/accounts/accounts-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Day book" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <DayBookReport />
    </RequirePermission>
  );
}
