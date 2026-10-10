"use client";

import { useEffect } from "react";
import { registerServiceWorker, SERVICE_WORKER_URL } from "@/lib/pwa";

/**
 * Registers public/sw.js (the installable app's offline shell) once the page has loaded, in
 * production builds only. Renders nothing.
 */
export function ServiceWorkerRegistration() {
  useEffect(() => {
    void registerServiceWorker(
      {
        production: process.env.NODE_ENV === "production",
        supported: "serviceWorker" in navigator,
        secureContext: window.isSecureContext,
      },
      () => navigator.serviceWorker.register(SERVICE_WORKER_URL, { scope: "/", updateViaCache: "none" }),
    );
  }, []);
  return null;
}
