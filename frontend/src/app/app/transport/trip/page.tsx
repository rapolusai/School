import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { TripScreen } from "@/components/views/transport/trip-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Bus trips" };

/** The driver's or attendant's phone screen for today's trips of their routes. */
export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.transportTrip}>
      <TripScreen />
    </RequirePermission>
  );
}
