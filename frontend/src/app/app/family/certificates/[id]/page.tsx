import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyCertificateView } from "@/components/views/certificates/family-certificates";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Certificate" };

export default async function Page({ params }: PageProps<"/app/family/certificates/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.dashboardView}>
      <FamilyCertificateView key={id} id={id} />
    </RequirePermission>
  );
}
