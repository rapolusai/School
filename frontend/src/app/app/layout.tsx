import type { Metadata } from "next";
import { AppShell } from "@/components/shell/app-shell";
import { PrivacyGate } from "@/components/views/privacy/privacy-gate";

export const metadata: Metadata = { robots: { index: false, follow: false } };

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <AppShell>
      <PrivacyGate>{children}</PrivacyGate>
    </AppShell>
  );
}
