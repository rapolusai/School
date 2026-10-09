import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { OfferLetterView } from "@/components/views/admissions/offer-letter-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "Offer letter" };

export default async function Page({ params }: PageProps<"/app/admissions/[id]/offer-letter">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.admissionsRead}>
      <OfferLetterView key={id} id={id} />
    </RequirePermission>
  );
}
