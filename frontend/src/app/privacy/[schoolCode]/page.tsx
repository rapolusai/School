import type { Metadata } from "next";
import { PublicNoticeView } from "@/components/views/privacy/public-notice-view";

export const metadata: Metadata = { title: "Privacy notice" };

/** A school's published privacy notice, current and older versions: no sign-in and no app shell. */
export default async function Page({ params }: PageProps<"/privacy/[schoolCode]">) {
  const { schoolCode } = await params;
  return <PublicNoticeView key={schoolCode} schoolCode={schoolCode} />;
}
