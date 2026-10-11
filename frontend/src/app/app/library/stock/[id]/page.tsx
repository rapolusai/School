import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StockCheckView } from "@/components/views/library/stock-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Stock verification" };

export default async function Page({ params }: PageProps<"/app/library/stock/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.libraryManage}>
      <StockCheckView key={id} id={id} />
    </RequirePermission>
  );
}
