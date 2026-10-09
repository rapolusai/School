import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { LeaveView } from "@/components/views/staff/leave-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Leave" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.leaveRequest}>
      <LeaveView initialTab={first(params.tab)} />
    </RequirePermission>
  );
}
