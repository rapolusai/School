import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StaffReceiptView } from "@/components/views/fees/receipts-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Receipt" };

export default async function Page({ params }: PageProps<"/app/fees/receipts/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.feesRead}>
      <StaffReceiptView key={id} id={id} />
    </RequirePermission>
  );
}
