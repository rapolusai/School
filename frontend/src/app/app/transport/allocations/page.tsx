import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AllocationsView } from "@/components/views/transport/allocations-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Students on the buses" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.transportRead}>
      <AllocationsView initialRouteId={first(params.routeId)} />
    </RequirePermission>
  );
}
