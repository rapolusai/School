import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ItemsView, type ItemsFilter } from "@/components/views/inventory/items-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Items" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  const filter = first(params.filter);
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <ItemsView initialFilter={filter === "low" || filter === "consumable" || filter === "asset" ? (filter as ItemsFilter) : "all"} />
    </RequirePermission>
  );
}
