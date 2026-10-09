"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useAuth } from "@/lib/auth";
import { landingPath } from "@/lib/permissions";

/** /app → the user's landing page (platform admins: Schools; everyone else: Dashboard). */
export default function AppIndex() {
  const { me } = useAuth();
  const router = useRouter();
  useEffect(() => {
    if (me) router.replace(landingPath(me));
  }, [me, router]);
  return null;
}
