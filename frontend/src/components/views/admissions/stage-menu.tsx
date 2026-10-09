"use client";

import { ArrowRightLeft, ChevronDown } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import { useI18n } from "@/lib/i18n";
import type { ApplicationStage } from "@/lib/types";
import { moveLabel } from "./admission-labels";

type Place = Pick<React.CSSProperties, "top" | "bottom" | "left" | "right">;

const DEFAULT_PLACE: Place = {};
const MENU_WIDTH = 240;
const MENU_HEIGHT = 280;
const GAP = 6;

/**
 * Where the menu goes, in window coordinates: below the button (above it when there is no room
 * below), lined up with its right edge (its left edge near the left of a small screen). Fixed
 * placement keeps the menu whole inside the scrolling board and table.
 */
export function placeNextTo(button: DOMRect, width: number, height: number): Place {
  const below = height - button.bottom;
  const vertical =
    below < MENU_HEIGHT && button.top > below
      ? { top: "auto", bottom: height - button.top + GAP }
      : { top: button.bottom + GAP, bottom: "auto" };
  const horizontal =
    button.right >= MENU_WIDTH + 8
      ? { left: "auto", right: Math.max(8, width - button.right) }
      : { left: Math.max(8, button.left), right: "auto" };
  return { ...vertical, ...horizontal };
}

/**
 * "Move" button with a menu of the stages this application may move to. Boards also allow it
 * by keyboard and screen reader, so moving never depends on dragging.
 */
export function StageMenu({
  childName,
  nextStages,
  onPick,
  size = "sm",
}: {
  childName: string;
  nextStages: ApplicationStage[];
  onPick: (stage: ApplicationStage) => void;
  size?: "sm" | "md";
}) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [place, setPlace] = useState<Place>(DEFAULT_PLACE);
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const menuId = useId();

  useEffect(() => {
    if (!open) return;
    menuRef.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    // The menu is placed against the window: it follows its button when the page or the board
    // scrolls, and closes once the button is out of sight.
    const onMove = () => {
      const button = buttonRef.current?.getBoundingClientRect();
      if (!button || button.bottom < 0 || button.top > window.innerHeight) setOpen(false);
      else setPlace(placeNextTo(button, window.innerWidth, window.innerHeight));
    };
    document.addEventListener("pointerdown", onPointerDown);
    window.addEventListener("scroll", onMove, true);
    window.addEventListener("resize", onMove);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      window.removeEventListener("scroll", onMove, true);
      window.removeEventListener("resize", onMove);
    };
  }, [open]);

  if (nextStages.length === 0) return null;

  const close = (restoreFocus: boolean) => {
    setOpen(false);
    if (restoreFocus) buttonRef.current?.focus();
  };

  const show = () => {
    const button = buttonRef.current;
    if (button) setPlace(placeNextTo(button.getBoundingClientRect(), window.innerWidth, window.innerHeight));
    setOpen(true);
  };

  const onMenuKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const items = Array.from(menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
    const index = items.indexOf(document.activeElement as HTMLElement);
    if (event.key === "Escape") {
      event.preventDefault();
      close(true);
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const step = event.key === "ArrowDown" ? 1 : -1;
      items[(index + step + items.length) % items.length]?.focus();
    } else if (event.key === "Home") {
      event.preventDefault();
      items[0]?.focus();
    } else if (event.key === "End") {
      event.preventDefault();
      items[items.length - 1]?.focus();
    } else if (event.key === "Tab") {
      close(false);
    }
  };

  return (
    <div ref={rootRef} className="relative flex-none">
      <button
        ref={buttonRef}
        type="button"
        className={size === "sm" ? "btn btn-sm" : "btn"}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={t("admissions.move.menuFor", { name: childName })}
        onClick={() => (open ? setOpen(false) : show())}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" && !open) {
            event.preventDefault();
            show();
          }
        }}
      >
        <ArrowRightLeft size={size === "sm" ? 14 : 18} aria-hidden="true" />
        {t("admissions.move.button")}
        <ChevronDown size={14} aria-hidden="true" />
      </button>
      {open ? (
        <div
          ref={menuRef}
          id={menuId}
          role="menu"
          aria-label={t("admissions.move.menuFor", { name: childName })}
          className="menu stage-menu"
          style={place}
          onKeyDown={onMenuKeyDown}
        >
          {nextStages.map((stage) => (
            <button
              key={stage}
              type="button"
              role="menuitem"
              tabIndex={-1}
              className={`menu-item${stage === "REJECTED" || stage === "WITHDRAWN" ? " menu-item-quiet" : ""}`}
              onClick={() => {
                close(false);
                onPick(stage);
              }}
            >
              {moveLabel(t, stage)}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
