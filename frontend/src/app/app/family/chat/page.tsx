import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { FamilyChatView } from "@/components/views/chat/chat-views";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Messages" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** The parent app: messages with the children's teachers (no phone numbers on either side). */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.childView}>
      <FamilyChatView initialId={first(params.c)} />
    </RequirePermission>
  );
}
