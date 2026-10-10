import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyAttendanceView } from "@/components/views/portal/family-attendance";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Attendance" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** The parent and student app: a child's (or the student's own) attendance, month by month. */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.dashboardView}>
      <FamilyAttendanceView initialChildId={first(params.child)} />
    </RequirePermission>
  );
}
