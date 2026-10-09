import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { CalendarView } from "@/components/views/communication/calendar-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Calendar" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.noticesRead}>
      <CalendarView initialMonth={first(params.month)} />
    </RequirePermission>
  );
}
