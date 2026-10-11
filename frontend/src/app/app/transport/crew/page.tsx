import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { CrewView } from "@/components/views/transport/crew-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Drivers and attendants" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.transportRead}>
      <CrewView />
    </RequirePermission>
  );
}
