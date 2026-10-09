import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PlatformInvoiceView } from "@/components/views/billing/platform-invoice-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Invoice" };

export default async function Page({ params }: PageProps<"/app/platform/schools/[id]/invoices/[invoiceId]">) {
  const { id, invoiceId } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.platformAdmin}>
      <PlatformInvoiceView key={`${id}:${invoiceId}`} tenantId={id} invoiceId={invoiceId} />
    </RequirePermission>
  );
}
