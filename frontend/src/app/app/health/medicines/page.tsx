import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { MedicinesView } from "@/components/views/health/medicines-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Medicine stock" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.healthRead}>
      <MedicinesView />
    </RequirePermission>
  );
}
