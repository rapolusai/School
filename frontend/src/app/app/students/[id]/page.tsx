import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StudentDetailView } from "@/components/views/students/student-detail-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Student" };

export default async function Page({ params }: PageProps<"/app/students/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.studentsRead}>
      <StudentDetailView key={id} id={id} />
    </RequirePermission>
  );
}
