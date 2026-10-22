import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { BooksReport } from "@/components/views/accounts/accounts-reports";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Cash and bank books" };

export default function Page() {
  return (
    <RequirePermission permission={PERMISSIONS.accountsRead}>
      <BooksReport />
    </RequirePermission>
  );
}
