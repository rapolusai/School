import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { LibraryReceiptView } from "@/components/views/library/receipt-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Library receipt" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** A fine receipt by number (?no=LIBF/2026-27/000001; the number has slashes, so it is not a path segment). */
export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const receiptNo = first((await searchParams).no) ?? "";
  return (
    <RequirePermission permission={PERMISSIONS.libraryManage}>
      <LibraryReceiptView key={receiptNo} receiptNo={receiptNo} />
    </RequirePermission>
  );
}
