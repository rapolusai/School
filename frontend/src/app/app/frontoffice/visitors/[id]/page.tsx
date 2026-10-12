import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { VisitorSlipView } from "@/components/views/frontoffice/visitor-slip";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Visitor slip" };

export default async function Page({ params }: PageProps<"/app/frontoffice/visitors/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.frontofficeRead}>
      <VisitorSlipView key={id} visitorId={id} />
    </RequirePermission>
  );
}
