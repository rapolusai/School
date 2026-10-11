import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PayrollStatutoryReport } from "@/components/views/payroll/payroll-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Statutory summary" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.payrollRead}>
      <PayrollStatutoryReport />
    </RequirePermission>
  );
}
