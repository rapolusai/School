import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PayrollComponentsView } from "@/components/views/payroll/components-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Salary components" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.payrollRead}>
      <PayrollComponentsView />
    </RequirePermission>
  );
}
