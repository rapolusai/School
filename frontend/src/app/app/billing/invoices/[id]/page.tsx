import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SchoolInvoiceView } from "@/components/views/billing/school-billing-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Invoice" };

export default async function Page({ params }: PageProps<"/app/billing/invoices/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.billingRead}>
      <SchoolInvoiceView key={id} id={id} />
    </RequirePermission>
  );
}
