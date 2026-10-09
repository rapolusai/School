import type { Metadata } from "next";
import { LoginView } from "@/components/views/login-view";

export const metadata: Metadata = { title: "Sign in" };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

export default async function LoginPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const params = await searchParams;
  return (
    <LoginView
      next={first(params.next) ?? null}
      school={first(params.school) ?? ""}
      signupDone={first(params.signup) === "done"}
    />
  );
}
