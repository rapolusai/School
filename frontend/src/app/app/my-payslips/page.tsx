import type { Metadata } from "next";
import { MyPayslipsView } from "@/components/views/payroll/payslip-document";

export const metadata: Metadata = { title: "My payslips" };

/** Any member of staff; the API turns parents and students away (403), which the view shows as access denied. */
export default function Page() {
  return <MyPayslipsView />;
}
