import type { Metadata } from "next";
import { SignupView } from "@/components/views/signup-view";

export const metadata: Metadata = { title: "Start your free trial" };

export default function SignupPage() {
  return <SignupView />;
}
