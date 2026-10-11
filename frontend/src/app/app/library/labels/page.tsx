import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { LabelsView } from "@/components/views/library/labels-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Book labels" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const titleId = first((await searchParams).titleId);
  return (
    <RequirePermission permission={PERMISSIONS.libraryManage}>
      <LabelsView key={titleId ?? "all"} initialTitleId={titleId} />
    </RequirePermission>
  );
}
