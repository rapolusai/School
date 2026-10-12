import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { TemplatesView } from "@/components/views/certificates/templates-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Certificate templates" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.certificatesRead}>
      <TemplatesView />
    </RequirePermission>
  );
}
