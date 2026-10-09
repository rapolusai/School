import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { MessagesView } from "@/components/views/messages/messages-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Messages" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.messagesRead}>
      <MessagesView />
    </RequirePermission>
  );
}
