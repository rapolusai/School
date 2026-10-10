import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { SchoolAccountView } from "@/components/views/billing/school-account-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "School account" };

export default async function Page({ params }: PageProps<"/app/platform/schools/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.platformAdmin}>
      <SchoolAccountView key={id} id={id} />
    </RequirePermission>
  );
}
