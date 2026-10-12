import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyPickupView } from "@/components/views/portal/family-pickup";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Early pickup" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** The parent app: ask for an early pickup and keep the list of people allowed to collect the child. */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.childView}>
      <FamilyPickupView initialChildId={first(params.child)} />
    </RequirePermission>
  );
}
