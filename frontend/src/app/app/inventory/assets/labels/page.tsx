import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AssetLabelsView } from "@/components/views/inventory/asset-labels-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Asset labels" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  const ids = (first(params.ids) ?? "").split(",").map((v) => v.trim()).filter(Boolean);
  return (
    <RequirePermission permission={PERMISSIONS.inventoryManage}>
      <AssetLabelsView key={ids.join(",")} ids={ids} />
    </RequirePermission>
  );
}
