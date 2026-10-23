import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { WatchListView } from "@/components/views/assist/watch-list-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Watch list" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.assistUse}>
      <WatchListView />
    </RequirePermission>
  );
}
