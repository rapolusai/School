import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ApplicationDetailView } from "@/components/views/admissions/application-detail-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Application" };

export default async function Page({ params }: PageProps<"/app/admissions/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.admissionsRead}>
      <ApplicationDetailView key={id} id={id} />
    </RequirePermission>
  );
}
