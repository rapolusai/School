import type { Metadata } from "next";
import { HomeworkDetailPage } from "@/components/views/homework/homework-pages";

export const metadata: Metadata = { title: "Homework" };

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ params, searchParams }: PageProps<"/app/homework/[id]">) {
  const { id } = await params;
  const query = await searchParams;
  const childId = first(query.child);
  return <HomeworkDetailPage key={`${id}:${childId ?? ""}`} id={id} childId={childId} />;
}
