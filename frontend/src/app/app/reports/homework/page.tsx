import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { HomeworkReportView } from "@/components/views/reports/homework-report";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Homework completion" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.homeworkManage}>
      <HomeworkReportView />
    </RequirePermission>
  );
}
