import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { CertificateView } from "@/components/views/certificates/certificate-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Certificate" };

export default async function Page({ params }: PageProps<"/app/certificates/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.certificatesRead}>
      <CertificateView key={id} id={id} />
    </RequirePermission>
  );
}
