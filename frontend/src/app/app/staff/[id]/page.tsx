import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StaffDetailView } from "@/components/views/staff/staff-detail-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Staff member" };

export default async function Page({ params }: PageProps<"/app/staff/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.staffRead}>
      <StaffDetailView key={id} id={id} />
    </RequirePermission>
  );
}
