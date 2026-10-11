import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { RouteDetailView } from "@/components/views/transport/route-detail-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Route" };

export default async function Page({ params }: PageProps<"/app/transport/routes/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.transportRead}>
      <RouteDetailView key={id} id={id} />
    </RequirePermission>
  );
}
