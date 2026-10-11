import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PayrollRunsView } from "@/components/views/payroll/runs-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Payroll" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.payrollRead}>
      <PayrollRunsView />
    </RequirePermission>
  );
}
