import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PayrollSettingsView } from "@/components/views/payroll/settings-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Payroll settings" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.payrollRead}>
      <PayrollSettingsView />
    </RequirePermission>
  );
}
