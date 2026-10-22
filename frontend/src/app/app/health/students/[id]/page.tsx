import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { MedicalRecordView } from "@/components/views/health/medical-record-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Medical record" };

/** A student's medical record: the nurse, leadership and the class teacher (every read is audited by the API). */
export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.healthRead}>
      <MedicalRecordView studentId={id} />
    </RequirePermission>
  );
}
