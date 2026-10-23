import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { OrdersView } from "@/components/views/inventory/orders-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Purchase orders" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <OrdersView openNew={first(params.new) === "1"} requisitionId={first(params.requisitionId)} rfqId={first(params.rfqId)} />
    </RequirePermission>
  );
}
