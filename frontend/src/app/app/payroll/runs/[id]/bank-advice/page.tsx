import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { BankAdviceView } from "@/components/views/payroll/bank-advice-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Bank advice" };

export default async function Page({ params }: PageProps<"/app/payroll/runs/[id]/bank-advice">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.payrollManage}>
      <BankAdviceView key={id} runId={id} />
    </RequirePermission>
  );
}
