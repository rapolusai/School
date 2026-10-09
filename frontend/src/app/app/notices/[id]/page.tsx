import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { NoticeDetailView } from "@/components/views/communication/notice-detail-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Circular" };

export default async function Page({ params }: PageProps<"/app/notices/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.noticesSend}>
      <NoticeDetailView key={id} id={id} />
    </RequirePermission>
  );
}
