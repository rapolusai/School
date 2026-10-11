import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ReportCardsView } from "@/components/views/exams/report-cards-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Report cards" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** A section's report cards for a term (?term=) or the year (?year=), ready to print. */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.examsRead}>
      <ReportCardsView
        initialSectionId={first(params.section)}
        initialTermId={first(params.term)}
        initialYearId={first(params.year)}
      />
    </RequirePermission>
  );
}
