"use client";

import { useEffect, useState } from "react";
import { api } from "./api";
import { hostelApi } from "./hostel-api";
import { isParentUser, type NavSubject } from "./permissions";

/** The family menu item that only boarders' parents see (docs/api/phase-3-hostel.md, "Families"). */
export const FAMILY_HOSTEL_NAV_KEY = "family-hostel";

/**
 * Which family menu items to hide for this person: "family-hostel" unless one of a parent's children boards in a
 * hostel. The children and their hostel standing are fetched once per sign-in; until they arrive, and for anyone who
 * is not a parent, the item stays hidden. A failed fetch also hides it (the home card still shows an error there).
 */
export function useFamilyHostelNav(me: (NavSubject & { id?: string }) | null): ReadonlySet<string> {
  const parent = me !== null && isParentUser(me);
  const [boarderOf, setBoarderOf] = useState<string | null>(null);
  const userId = me?.id ?? "";
  useEffect(() => {
    if (!parent) return;
    let cancelled = false;
    (async () => {
      try {
        const children = await api.myChildren();
        const standings = await Promise.all(
          children.map((child) => hostelApi.childHostel(child.id).then((h) => h.boarder).catch(() => false)),
        );
        if (!cancelled) setBoarderOf(standings.some(Boolean) ? userId : null);
      } catch {
        if (!cancelled) setBoarderOf(null);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [parent, userId]);
  const hidden = new Set<string>();
  if (!parent || boarderOf !== userId) hidden.add(FAMILY_HOSTEL_NAV_KEY);
  return hidden;
}
