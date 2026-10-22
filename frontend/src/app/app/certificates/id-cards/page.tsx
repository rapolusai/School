import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { IdCardsView } from "@/components/views/certificates/id-cards-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "ID cards" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.certificatesRead}>
      <IdCardsView />
    </RequirePermission>
  );
}
