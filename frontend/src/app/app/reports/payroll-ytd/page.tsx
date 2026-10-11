import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PayrollYearToDateReport } from "@/components/views/payroll/payroll-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Pay year to date" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.payrollRead}>
      <PayrollYearToDateReport />
    </RequirePermission>
  );
}
