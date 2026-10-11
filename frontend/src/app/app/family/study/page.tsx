import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyStudyView } from "@/components/views/lessons/family-study";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Study" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** The parent and student app: study material and online tests (a parent sees the chosen child's, read-only). */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.dashboardView}>
      <FamilyStudyView initialChildId={first(params.child)} />
    </RequirePermission>
  );
}
