import type { Metadata } from "next";
import { HomeworkPage } from "@/components/views/homework/homework-pages";

export const metadata: Metadata = { title: "Homework" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** Staff with homework.manage, students and parents each get their own view; see HomeworkPage. */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return <HomeworkPage initialChildId={first(params.child)} />;
}
