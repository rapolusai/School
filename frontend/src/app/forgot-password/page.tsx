import type { Metadata } from "next";
import { ForgotPasswordView } from "@/components/views/forgot-password-view";

export const metadata: Metadata = { title: "Forgot password" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function ForgotPasswordPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return <ForgotPasswordView school={first(params.school) ?? ""} />;
}
