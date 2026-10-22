import type { Metadata } from "next";
import { RequireAny } from "@/components/views/exams/exams-shared";
import { HOSTEL_PERMISSIONS } from "@/components/views/hostel/hostel-shared";
import { BoardersView } from "@/components/views/hostel/boarders-view";

export const metadata: Metadata = { title: "Boarders" };

export default function Page() {
  return (
    <RequireAny permissions={HOSTEL_PERMISSIONS}>
      <BoardersView />
    </RequireAny>
  );
}
