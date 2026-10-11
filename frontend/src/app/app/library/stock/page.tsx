import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { StockChecksView } from "@/components/views/library/stock-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Stock verification" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.libraryManage}>
      <StockChecksView />
    </RequirePermission>
  );
}
