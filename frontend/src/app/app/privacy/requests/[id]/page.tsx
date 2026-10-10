import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RequestDetailView } from "@/components/views/privacy/request-detail-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Data request" };

export default async function Page({ params }: PageProps<"/app/privacy/requests/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.privacyManage}>
      <RequestDetailView key={id} id={id} />
    </RequirePermission>
  );
}
