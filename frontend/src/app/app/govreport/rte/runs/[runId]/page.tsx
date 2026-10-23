import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RteRunView } from "@/components/views/govreport/rte-run-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "RTE lottery result" };

export default async function Page({ params }: PageProps<"/app/govreport/rte/runs/[runId]">) {
  const { runId } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.govreportRead}>
      <RteRunView key={runId} runId={runId} />
    </RequirePermission>
  );
}
