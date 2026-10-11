import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { VehiclesView } from "@/components/views/transport/vehicles-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Vehicles" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.transportRead}>
      <VehiclesView />
    </RequirePermission>
  );
}
