import type { Metadata } from "next";
import { RequireAnyOf } from "@/components/views/chat/chat-shared";
import { ChatSettingsView } from "@/components/views/chat/chat-views";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Chat settings" };

/** The teacher's away note (chat.use) and the school's hours for messages (chat.oversee changes them). */
export default function Page() {
  return (
    <RequireAnyOf permissions={[PERMISSIONS.chatUse, PERMISSIONS.chatOversee]}>
      <ChatSettingsView />
    </RequireAnyOf>
  );
}
