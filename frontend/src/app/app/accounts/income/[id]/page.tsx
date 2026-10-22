import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { IncomeReceiptPage } from "@/components/views/accounts/income-receipt-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Income receipt" };

export default async function Page({ params }: PageProps<"/app/accounts/income/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <IncomeReceiptPage key={id} id={id} />
    </RequirePermission>
  );
}
