"use client";

import { LogOut } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import { initials } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import type { Me } from "@/lib/types";

export function UserMenu({
  me,
  roleLabel,
  onSignOut,
}: {
  me: Me;
  roleLabel: string;
  onSignOut: () => void;
}) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
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
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

  const close = (restoreFocus: boolean) => {
    setOpen(false);
    if (restoreFocus) buttonRef.current?.focus();
  };

  const onMenuKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const items = Array.from(
      menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [],
    );
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
        className="me"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={`${t("shell.accountMenu")}: ${me.name}`}
        onClick={() => setOpen((value) => !value)}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" && !open) {
            event.preventDefault();
            setOpen(true);
          }
        }}
      >
        <span className="avatar" aria-hidden="true">
          {initials(me.name)}
        </span>
        <span className="who" aria-hidden="true">
          <b>{me.name}</b>
          <span>{roleLabel}</span>
        </span>
      </button>
      {open ? (
        <div
          ref={menuRef}
          id={menuId}
          role="menu"
          aria-label={t("shell.accountMenu")}
          className="menu"
          onKeyDown={onMenuKeyDown}
        >
          <div className="px-2.5 pt-2 pb-3 border-b border-line mb-1.5 min-w-0" role="none">
            <p className="text-xs text-ink-3">{t("shell.signedInAs")}</p>
            <p className="font-semibold truncate">{me.name}</p>
            <p className="text-sm text-ink-2 truncate">{me.email}</p>
            {me.tenant ? (
              <p className="mono text-ink-3 mt-1 truncate">
                {t("shell.schoolCode", { code: me.tenant.code })}
              </p>
            ) : null}
          </div>
          <button
            type="button"
            role="menuitem"
            className="menu-item"
            tabIndex={-1}
            onClick={() => {
              setOpen(false);
              onSignOut();
            }}
          >
            <LogOut size={18} aria-hidden="true" />
            {t("shell.signOut")}
          </button>
        </div>
      ) : null}
    </div>
  );
}
