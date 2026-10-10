import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyFeesView } from "@/components/views/portal/family-fees";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Fees" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** The parent and student app: a parent's fees for the chosen child, with online payment. */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.childView}>
      <FamilyFeesView initialChildId={first(params.child)} />
    </RequirePermission>
  );
}
