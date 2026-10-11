import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { OversightDetailView } from "@/components/views/chat/oversight-views";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Conversation" };

/** One conversation as oversight reads it; every opening is audited. */
export default async function Page({ params }: PageProps<"/app/chat/oversight/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.chatOversee}>
      <OversightDetailView key={id} id={id} />
    </RequirePermission>
  );
}
