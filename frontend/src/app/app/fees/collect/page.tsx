import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { CollectView } from "@/components/views/fees/collect-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Collect fee" };

export default async function Page({ searchParams }: PageProps<"/app/fees/collect">) {
  const { student } = await searchParams;
  const studentId = Array.isArray(student) ? student[0] : student;
  return (
    <RequirePermission permission={PERMISSIONS.feesCollect}>
      <CollectView key={studentId ?? "search"} initialStudentId={studentId} />
    </RequirePermission>
  );
}
