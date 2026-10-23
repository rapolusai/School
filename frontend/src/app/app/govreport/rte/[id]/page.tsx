import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RteIntakeView } from "@/components/views/govreport/rte-intake-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "RTE intake" };

export default async function Page({ params }: PageProps<"/app/govreport/rte/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.govreportRead}>
      <RteIntakeView key={id} id={id} />
    </RequirePermission>
  );
}
