import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AdmissionsView } from "@/components/views/admissions/admissions-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Admissions" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.admissionsRead}>
      <AdmissionsView />
    </RequirePermission>
  );
}
