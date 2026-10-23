import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { VerificationRoundView } from "@/components/views/inventory/verification-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Verification round" };

export default async function Page({ params }: PageProps<"/app/inventory/verification/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.inventoryManage}>
      <VerificationRoundView key={id} id={id} />
    </RequirePermission>
  );
}
