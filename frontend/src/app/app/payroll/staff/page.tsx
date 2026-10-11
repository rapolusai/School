import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PayStaffView } from "@/components/views/payroll/pay-staff-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Staff pay" };

export default async function Page({ searchParams }: PageProps<"/app/payroll/staff">) {
  const { filter } = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.payrollRead}>
      <PayStaffView initialFilter={filter === "structure" ? "structure" : filter === "bank" ? "bank" : "all"} />
    </RequirePermission>
  );
}
