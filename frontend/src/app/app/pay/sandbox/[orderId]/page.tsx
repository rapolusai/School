import type { Metadata } from "next";
import { SandboxCheckoutView } from "@/components/views/fees/sandbox-checkout-view";

export const metadata: Metadata = { title: "Sandbox payment" };

/** Parents (child.view) and fee staff (fees.collect) may open it; the view checks either. */
export default async function Page({ params }: PageProps<"/app/pay/sandbox/[orderId]">) {
  const { orderId } = await params;
  return <SandboxCheckoutView key={orderId} gatewayOrderId={orderId} />;
}
