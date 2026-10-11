import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PayrollPayslipView } from "@/components/views/payroll/payslip-document";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Payslip" };

export default async function Page({ params }: PageProps<"/app/payroll/payslips/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.payrollRead}>
      <PayrollPayslipView key={id} id={id} />
    </RequirePermission>
  );
}
