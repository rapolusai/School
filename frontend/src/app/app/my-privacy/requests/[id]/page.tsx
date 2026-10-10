import type { Metadata } from "next";
import { RequirePermission } from "@/components/access";
import { MyRequestView } from "@/components/views/privacy/my-request-view";
import { PERMISSIONS } from "@/lib/permissions";

export const metadata: Metadata = { title: "My request" };

export default async function Page({ params }: PageProps<"/app/my-privacy/requests/[id]">) {
  const { id } = await params;
  return (
    <RequirePermission permission={PERMISSIONS.childView}>
      <MyRequestView key={id} id={id} />
    </RequirePermission>
  );
}
