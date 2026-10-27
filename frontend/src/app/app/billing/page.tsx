import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SchoolBillingView } from "@/components/views/billing/school-billing-view";
import { PERMISSIONS } from "@/lib/permissions";
import { planModule } from "@/lib/plan-access";

export const metadata: Metadata = { title: "Billing" };

export default async function Page({ searchParams }: PageProps<"/app/billing">) {
  const query = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.billingRead}>
      <SchoolBillingView upgrade={planModule(query.upgrade)} />
    </RequirePermission>
  );
}
