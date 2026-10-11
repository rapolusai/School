import type { Metadata } from "next";
import { RequireAnyOf } from "@/components/views/chat/chat-shared";
import { StaffChatView } from "@/components/views/chat/chat-views";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Chat" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** Parent-teacher messages for staff (chat.use); chat.oversee alone opens the oversight list. */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequireAnyOf permissions={[PERMISSIONS.chatUse, PERMISSIONS.chatOversee]}>
      <StaffChatView initialId={first(params.c)} />
    </RequireAnyOf>
  );
}
