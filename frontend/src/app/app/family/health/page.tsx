import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyHealthView } from "@/components/views/portal/family-health";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Health" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** The parent app: the child's allergies, conditions and consent, and the infirmary's visits. */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.childView}>
      <FamilyHealthView initialChildId={first(params.child)} />
    </RequirePermission>
  );
}
