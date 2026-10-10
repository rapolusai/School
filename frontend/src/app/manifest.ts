import type { MetadataRoute } from "next";

/**
 * The web app manifest (served at /manifest.webmanifest): lets parents and students add Akshara to
 * their home screen. Icons live in public/icons; the service worker is public/sw.js.
 */
export default function manifest(): MetadataRoute.Manifest {
  return {
    id: "/",
    name: "Akshara School Cloud",
    short_name: "Akshara",
    description: "Attendance, homework, fees, notices and leave for your child's school.",
    lang: "en",
    start_url: "/app/dashboard",
    scope: "/",
    display: "standalone",
    background_color: "#f5f6f3",
    theme_color: "#f5f6f3",
    categories: ["education"],
    icons: [
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      { src: "/icons/maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
      { src: "/icons/icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any" },
    ],
  };
}
