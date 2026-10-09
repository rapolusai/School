import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AttendanceView } from "@/components/views/attendance/attendance-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Attendance" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.attendanceRead}>
      <AttendanceView initialDate={first(params.date)} initialSectionId={first(params.section)} />
    </RequirePermission>
  );
}
