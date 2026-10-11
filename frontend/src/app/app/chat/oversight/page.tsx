import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { OversightView } from "@/components/views/chat/oversight-views";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Chat oversight" };

/** Every conversation and the reports, for safeguarding (chat.oversee). */
export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.chatOversee}>
      <OversightView />
    </RequirePermission>
  );
}
