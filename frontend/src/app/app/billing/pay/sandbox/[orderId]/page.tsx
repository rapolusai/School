import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { BillingSandboxCheckoutView } from "@/components/views/billing/pay-online";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Sandbox payment" };

/** School Admins (billing.read and settings.manage) pay Akshara's invoices; the view checks settings.manage too. */
export default async function Page({ params }: PageProps<"/app/billing/pay/sandbox/[orderId]">) {
  const { orderId } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.billingRead}>
      <BillingSandboxCheckoutView key={orderId} gatewayOrderId={orderId} />
    </RequirePermission>
  );
}
