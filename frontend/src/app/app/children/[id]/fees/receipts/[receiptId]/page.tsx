import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ChildReceiptView } from "@/components/views/fees/child-fees";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Receipt" };

export default async function Page({
  params,
  searchParams,
}: PageProps<"/app/children/[id]/fees/receipts/[receiptId]">) {
  const { id, receiptId } = await params;
  const { paid } = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.childView}>
      <ChildReceiptView key={receiptId} studentId={id} receiptId={receiptId} justPaid={paid === "1"} />
    </RequirePermission>
  );
}
