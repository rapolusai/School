import type { NextConfig } from "next";

/**
 * Where the Spring Boot API lives. Browser calls go to `/api/*` on this origin and are
 * proxied there, so the httpOnly refresh cookie (SameSite=Strict, path /api/auth) stays
 * first-party.
 *
 * Note: next.config is evaluated during `next build` and serialised into the standalone
 * server, so this value is fixed at build time (see the Dockerfile's API_URL build arg).
 */
const apiUrl = (process.env.API_URL ?? "http://localhost:8080").replace(/\/+$/, "");

const isDev = process.env.NODE_ENV === "development";

/**
 * Razorpay Checkout (src/lib/razorpay-checkout.ts): its script, the payment window it opens in a frame, and the
 * calls and images that window makes. Loaded only when a parent pays a Razorpay order.
 */
const razorpay = {
  script: "https://checkout.razorpay.com",
  frame: "https://api.razorpay.com https://checkout.razorpay.com",
  connect: "https://api.razorpay.com https://lumberjack.razorpay.com",
  img: "https://cdn.razorpay.com",
};

const csp = [
  "default-src 'self'",
  // Next.js injects inline bootstrap scripts; a nonce-based policy would force dynamic rendering.
  `script-src 'self' 'unsafe-inline'${isDev ? " 'unsafe-eval'" : ""} ${razorpay.script}`,
  "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
  "font-src 'self' https://fonts.gstatic.com data:",
  `img-src 'self' data: blob: ${razorpay.img}`,
  `connect-src 'self'${isDev ? " ws: wss:" : ""} ${razorpay.connect}`,
  `frame-src ${razorpay.frame}`,
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

const securityHeaders = [
  { key: "Content-Security-Policy", value: csp },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  {
    key: "Permissions-Policy",
    value:
      'camera=(self), microphone=(), geolocation=(self), payment=(self "https://api.razorpay.com"), usb=(), interest-cohort=()',
  },
];

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  reactStrictMode: true,
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${apiUrl}/api/:path*` }];
  },
  async headers() {
    return [
      { source: "/:path*", headers: securityHeaders },
      // The installable app's service worker: always fetched fresh so an update reaches every phone.
      { source: "/sw.js", headers: [{ key: "Cache-Control", value: "no-cache, no-store, must-revalidate" }] },
    ];
  },
};

export default nextConfig;
