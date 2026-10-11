import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { ExamAnalysisReportView } from "@/components/views/reports/exam-analysis-report";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Result analysis" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.examsRead}>
      <ExamAnalysisReportView initialExamId={first(params.exam)} initialClassId={first(params.class)} />
    </RequirePermission>
  );
}
