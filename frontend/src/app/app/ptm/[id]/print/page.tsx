import type { Metadata } from "next";
import { RequireAnyOf } from "@/components/views/chat/chat-shared";
import { PtmPrintView } from "@/components/views/chat/ptm-print";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "PTM schedules" };

/** Printable schedules: per teacher or per class (ptm.manage), or the teacher's own. */
export default async function Page({ params, searchParams }: PageProps<"/app/ptm/[id]/print">) {
  const { id } = await params;
  const query = await searchParams;
  return (
    <RequireAnyOf permissions={[PERMISSIONS.ptmManage, PERMISSIONS.chatUse]}>
      <PtmPrintView key={id} id={id} initialBy={query.by === "class" ? "class" : "teacher"} />
    </RequireAnyOf>
  );
}
