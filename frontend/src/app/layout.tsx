import type { Metadata, Viewport } from "next";
import { Providers } from "@/components/providers";
import { THEME_INIT_SCRIPT } from "@/lib/theme-script";
import "./globals.css";

const FONTS_HREF =
  "https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,500;12..96,700&family=Figtree:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500&family=Noto+Sans+Devanagari:wght@400;500;600;700&display=swap";

export const metadata: Metadata = {
  title: { default: "Akshara School Cloud", template: "%s · Akshara" },
  description: "School management for Indian schools: users, roles and audit trail.",
  applicationName: "Akshara",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f5f6f3" },
    { media: "(prefers-color-scheme: dark)", color: "#0f1512" },
  ],
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        {/* Loaded via <link> (not next/font) so builds never need to reach Google. */}
        <link rel="stylesheet" href={FONTS_HREF} />
      </head>
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
