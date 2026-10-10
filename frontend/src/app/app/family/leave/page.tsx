import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyLeaveView } from "@/components/views/portal/family-leave";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Leave" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** The parent and student app: a parent's absence notes for their child; a student's own, read only. */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.dashboardView}>
      <FamilyLeaveView initialChildId={first(params.child)} />
    </RequirePermission>
  );
}
