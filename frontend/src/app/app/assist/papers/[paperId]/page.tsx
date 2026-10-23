import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { PaperDetailView } from "@/components/views/assist/papers-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Draft paper" };

export default async function Page({ params }: PageProps<"/app/assist/papers/[paperId]">) {
  const { paperId } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.assistUse}>
      <PaperDetailView key={paperId} paperId={paperId} />
    </RequirePermission>
  );
}
