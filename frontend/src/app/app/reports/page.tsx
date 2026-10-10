import type { Metadata } from "next";
import { ReportsHubView } from "@/components/views/reports/reports-hub";

export const metadata: Metadata = { title: "Reports" };

/** Open to anyone holding one of the reports' read permissions; the view checks and lists only those reports. */
export default function Page() {
  return <ReportsHubView />;
}
