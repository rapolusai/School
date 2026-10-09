import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { AttendanceSettingsView } from "@/components/views/attendance/attendance-settings-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Absence alerts" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.settingsManage}>
      <AttendanceSettingsView />
    </RequirePermission>
  );
}
