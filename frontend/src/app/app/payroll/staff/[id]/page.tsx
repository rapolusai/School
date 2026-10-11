import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PayStaffDetailView } from "@/components/views/payroll/pay-staff-detail-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Staff pay" };

export default async function Page({ params }: PageProps<"/app/payroll/staff/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.payrollRead}>
      <PayStaffDetailView key={id} userId={id} />
    </RequirePermission>
  );
}
