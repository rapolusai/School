import type { Me } from "./types";

/** Roles that get the parent and student app instead of the staff dashboard and menu. */
export const FAMILY_ROLES: readonly string[] = ["PARENT", "STUDENT"];

/**
 * True for someone whose every role is PARENT or STUDENT. A teacher who is also a parent keeps the
 * staff app (it already shows "My children"). Pure.
 */
export function isFamilyMember(
  me: (Pick<Me, "platformAdmin"> & Partial<Pick<Me, "roles">>) | null | undefined,
): boolean {
  const roles = me?.roles ?? [];
  if (!me || me.platformAdmin || roles.length === 0) return false;
  return roles.every((role) => FAMILY_ROLES.includes(role));
}

/** A parent (not only a student): may see fees and apply for leave. Pure. */
export function isParent(me: Pick<Me, "roles"> | null | undefined): boolean {
  return Boolean(me?.roles.includes("PARENT"));
}

/** Browser storage key for the child a parent last looked at (an id only, nothing personal). */
export const CHILD_STORAGE_KEY = "akshara.family.child";

/** The child chosen last time on this device, if storage is available. */
export function rememberedChild(): string | null {
  try {
    return window.localStorage.getItem(CHILD_STORAGE_KEY);
  } catch {
    return null;
  }
}

/** Remembers the chosen child on this device; silently does nothing when storage is blocked. */
export function rememberChild(childId: string): void {
  try {
    window.localStorage.setItem(CHILD_STORAGE_KEY, childId);
  } catch {
    // Private windows and blocked storage: the switcher still works for this visit.
  }
}

/** The child to show: the chosen one if it is still linked, else the first. Pure. */
export function pickChild<T extends { id: string }>(children: readonly T[], chosen: string | null | undefined): T | undefined {
  return children.find((c) => c.id === chosen) ?? children[0];
}
