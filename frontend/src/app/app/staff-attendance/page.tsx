import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StaffAttendanceView } from "@/components/views/staff/staff-attendance-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Staff attendance" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.staffRead}>
      <StaffAttendanceView initialTab={first(params.tab)} initialDate={first(params.date)} />
    </RequirePermission>
  );
}
