import type { Metadata } from "next";
import { MyPayslipView } from "@/components/views/payroll/payslip-document";

export const metadata: Metadata = { title: "Payslip" };

export default async function Page({ params }: PageProps<"/app/my-payslips/[id]">) {
  const { id } = await params;
  return <MyPayslipView key={id} id={id} />;
}
