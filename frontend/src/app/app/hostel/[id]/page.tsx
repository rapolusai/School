import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { HOSTEL_PERMISSIONS } from "@/components/views/hostel/hostel-shared";
import { HostelDetailView } from "@/components/views/hostel/hostels-view";

export const metadata: Metadata = { title: "Hostel" };

export default async function Page({ params }: PageProps<"/app/hostel/[id]">) {
  const { id } = await params;
  return (
    <RequireAny permissions={HOSTEL_PERMISSIONS}>
      <HostelDetailView key={id} id={id} />
    </RequireAny>
  );
}
