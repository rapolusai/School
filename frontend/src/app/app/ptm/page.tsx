import type { Metadata } from "next";
import { RequireAnyOf } from "@/components/views/chat/chat-shared";
import { PtmHomeView } from "@/components/views/chat/ptm-views";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "PTM" };

/** Parent-teacher meetings: leadership sets them up (ptm.manage); teachers see their schedule (chat.use). */
export default function Page() {
  return (
    <RequireAnyOf permissions={[PERMISSIONS.ptmManage, PERMISSIONS.chatUse]}>
      <PtmHomeView />
    </RequireAnyOf>
  );
}
