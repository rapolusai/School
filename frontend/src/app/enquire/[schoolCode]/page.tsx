import type { Metadata } from "next";
import { PublicEnquiryView } from "@/components/views/admissions/public-enquiry-view";

export const metadata: Metadata = { title: "Admission enquiry" };

/** A school's public admission enquiry form: no sign-in and no app shell. */
export default async function Page({ params }: PageProps<"/enquire/[schoolCode]">) {
  const { schoolCode } = await params;
  return <PublicEnquiryView key={schoolCode} schoolCode={schoolCode} />;
}
