import type { Metadata } from "next";
import { VerifyView } from "@/components/views/certificates/verify-view";

export const metadata: Metadata = { title: "Verify certificate" };

/** The page a certificate's QR code points at: no sign-in and no app shell. */
export default async function Page({ params }: PageProps<"/verify/[token]">) {
  const { token } = await params;
  return <VerifyView key={token} token={token} />;
}
