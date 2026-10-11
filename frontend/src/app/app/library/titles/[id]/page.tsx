import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { TitleDetailView } from "@/components/views/library/title-detail-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Book" };

export default async function Page({ params }: PageProps<"/app/library/titles/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.libraryRead}>
      <TitleDetailView key={id} id={id} />
    </RequirePermission>
  );
}
