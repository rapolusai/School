import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { BoardView } from "@/components/views/communication/board-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Notice board" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function Page({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <RequirePermission permission={PERMISSIONS.noticesRead}>
      <BoardView initialOpenId={first(params.open)} />
    </RequirePermission>
  );
}
