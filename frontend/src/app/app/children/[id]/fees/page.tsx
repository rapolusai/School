import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ChildFeesView } from "@/components/views/fees/child-fees";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Fees" };

export default async function Page({ params }: PageProps<"/app/children/[id]/fees">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.childView}>
      <ChildFeesView key={id} id={id} />
    </RequirePermission>
  );
}
