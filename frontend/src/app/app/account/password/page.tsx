import type { Metadata } from "next";
import { ChangePasswordView } from "@/components/views/change-password-view";

export const metadata: Metadata = { title: "Change password" };

/** Any signed-in school user. */
export default function Page() {
  return <ChangePasswordView />;
}
