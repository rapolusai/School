import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RunPayslipsView } from "@/components/views/payroll/payslip-document";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Payslips" };

export default async function Page({ params }: PageProps<"/app/payroll/runs/[id]/payslips">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.payrollRead}>
      <RunPayslipsView key={id} runId={id} />
    </RequirePermission>
  );
}
