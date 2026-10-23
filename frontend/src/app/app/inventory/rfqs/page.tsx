import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RfqsView } from "@/components/views/inventory/rfqs-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Quotations" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.inventoryManage}>
      <RfqsView requisitionId={first(params.requisitionId)} />
    </RequirePermission>
  );
}
