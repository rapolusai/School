import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PayrollRunView } from "@/components/views/payroll/run-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Payroll run" };

export default async function Page({ params }: PageProps<"/app/payroll/runs/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.payrollRead}>
      <PayrollRunView key={id} id={id} />
    </RequirePermission>
  );
}
