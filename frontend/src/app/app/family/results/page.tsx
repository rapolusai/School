import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyResultsView } from "@/components/views/exams/family-results";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Results" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** The parent and student app: published results, the report card and the coming date sheet. */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.dashboardView}>
      <FamilyResultsView initialChildId={first(params.child)} />
    </RequirePermission>
  );
}
