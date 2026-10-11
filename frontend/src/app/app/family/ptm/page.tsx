import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyPtmView } from "@/components/views/chat/family-ptm";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "PTM" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** The parent app: book, change or cancel a parent-teacher meeting slot for each child. */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.childView}>
      <FamilyPtmView initialChildId={first(params.child)} />
    </RequirePermission>
  );
}
