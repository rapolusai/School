import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RequisitionsView } from "@/components/views/inventory/requisitions-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Requisitions" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.inventoryRead}>
      <RequisitionsView openNew={first(params.new) === "1"} />
    </RequirePermission>
  );
}
