"use client";

import { X } from "lucide-react";
import { useEffect, useEffectEvent, useId, useRef, useSyncExternalStore } from "react";
import { createPortal } from "react-dom";

const FOCUSABLE = [
  "a[href]",
  "button:not([disabled])",
  "input:not([disabled]):not([type='hidden'])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[tabindex]:not([tabindex='-1'])",
].join(",");

function focusableIn(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
    (el) => !el.hasAttribute("inert") && el.getAttribute("aria-hidden") !== "true",
  );
}

const noopSubscribe = () => () => {};

type DialogProps = {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  /** "modal" is centred; "sheet" slides up from the bottom (phone "More" menu). */
  variant?: "modal" | "sheet";
  closeLabel?: string;
  children: React.ReactNode;
};

/**
 * Accessible modal dialog: role="dialog" + aria-modal, labelled by its title, focus moves in
 * on open and is trapped (Tab / Shift+Tab wrap), Esc and the scrim close it, and focus
 * returns to the element that opened it.
 */
export function Dialog({
  open,
  onClose,
  title,
  description,
  variant = "modal",
  closeLabel = "Close",
  children,
}: DialogProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  const mounted = useSyncExternalStore(noopSubscribe, () => true, () => false);
  const requestClose = useEffectEvent(() => onClose());

  useEffect(() => {
    if (!open) return;
    const panel = panelRef.current;
    if (!panel) return;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;

    const preferred =
      panel.querySelector<HTMLElement>("[data-autofocus]") ??
      focusableIn(panel).find((el) => !el.hasAttribute("data-dialog-close")) ??
      panel;
    preferred.focus();

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.stopPropagation();
        requestClose();
        return;
      }
      if (event.key !== "Tab") return;
      const items = focusableIn(panel);
      if (items.length === 0) {
        event.preventDefault();
        panel.focus();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;
      if (event.shiftKey && (active === first || !panel.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (active === last || !panel.contains(active))) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown, true);

    return () => {
      document.removeEventListener("keydown", onKeyDown, true);
      document.body.style.overflow = previousOverflow;
      if (opener && opener.isConnected) opener.focus();
    };
  }, [open]);

  if (!open || !mounted) return null;

  return createPortal(
    <div
      className={variant === "sheet" ? "scrim scrim-sheet" : "scrim"}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={panelRef}
        className={variant === "sheet" ? "sheet" : "modal"}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
      >
        <div className="ov-head">
          <div className="min-w-0">
            <h2 id={titleId}>{title}</h2>
            {description ? (
              <p id={descriptionId} className="mt-1 text-sm text-ink-2">
                {description}
              </p>
            ) : null}
          </div>
          <button
            type="button"
            className="iconbtn"
            onClick={onClose}
            aria-label={closeLabel}
            data-dialog-close
          >
            <X size={20} aria-hidden="true" />
          </button>
        </div>
        {children}
      </div>
    </div>,
    document.body,
  );
}
