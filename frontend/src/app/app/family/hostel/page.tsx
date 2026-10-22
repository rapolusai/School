import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyHostelView } from "@/components/views/portal/family-hostel";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Hostel" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** The parent app: a boarder's bed, warden and roll call, and the outing and home-leave passes. */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.childView}>
      <FamilyHostelView initialChildId={first(params.child)} />
    </RequirePermission>
  );
}
