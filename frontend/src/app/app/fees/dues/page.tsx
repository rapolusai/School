import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { DuesView } from "@/components/views/fees/dues-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Fee dues" };

export default async function Page({ searchParams }: PageProps<"/app/fees/dues">) {
  const { view } = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.feesRead}>
      <DuesView initialTab={view === "overdue" ? "overdue" : "outstanding"} />
    </RequirePermission>
  );
}
