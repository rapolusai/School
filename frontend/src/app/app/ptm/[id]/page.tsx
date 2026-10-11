import type { Metadata } from "next";
import { RequireAnyOf } from "@/components/views/chat/chat-shared";
import { PtmEventView } from "@/components/views/chat/ptm-views";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "PTM" };

/** One meeting: every schedule for ptm.manage, the teacher's own otherwise. */
export default async function Page({ params }: PageProps<"/app/ptm/[id]">) {
  const { id } = await params;
  return (
    <RequireAnyOf permissions={[PERMISSIONS.ptmManage, PERMISSIONS.chatUse]}>
      <PtmEventView key={id} id={id} />
    </RequireAnyOf>
  );
}
