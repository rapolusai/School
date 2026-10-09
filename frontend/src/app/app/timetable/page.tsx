import type { Metadata } from "next";
import { TimetablePage } from "@/components/views/timetable/timetable-view";

export const metadata: Metadata = { title: "Timetable" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** Staff (timetable.read), students and parents each get their own view; see TimetablePage. */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <TimetablePage
      initialTab={first(params.tab)}
      initialSectionId={first(params.section)}
      initialDate={first(params.date)}
      initialChildId={first(params.child)}
    />
  );
}
