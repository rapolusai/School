import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { HallTicketsView } from "@/components/views/exams/hall-tickets";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Hall tickets" };

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ params, searchParams }: PageProps<"/app/exams/[examId]/[classId]/hall-tickets">) {
  const { examId, classId } = await params;
  const query = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.examsRead}>
      <HallTicketsView examId={examId} classId={classId} initialSectionId={first(query.section)} />
    </RequirePermission>
  );
}
