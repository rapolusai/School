import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PayrollRegisterReport } from "@/components/views/payroll/payroll-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Payroll register" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.payrollRead}>
      <PayrollRegisterReport />
    </RequirePermission>
  );
}
