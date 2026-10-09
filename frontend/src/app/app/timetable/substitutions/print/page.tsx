import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SubstitutionSheet } from "@/components/views/timetable/substitution-sheet";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Substitution sheet" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.timetableRead}>
      <SubstitutionSheet date={first(params.date)} />
    </RequirePermission>
  );
}
