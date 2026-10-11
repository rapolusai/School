import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { CatalogueView } from "@/components/views/library/catalogue-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Library" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.libraryRead}>
      <CatalogueView />
    </RequirePermission>
  );
}
