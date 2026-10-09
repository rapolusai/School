import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ComposeView } from "@/components/views/communication/compose-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Edit circular" };

export default async function Page({ params }: PageProps<"/app/notices/[id]/edit">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.noticesSend}>
      <ComposeView key={id} id={id} />
    </RequirePermission>
  );
}
