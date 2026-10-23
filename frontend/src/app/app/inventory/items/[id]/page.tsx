import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ItemLedgerView } from "@/components/views/inventory/item-ledger-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Item" };

export default async function Page({ params }: PageProps<"/app/inventory/items/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <ItemLedgerView key={id} id={id} />
    </RequirePermission>
  );
}
