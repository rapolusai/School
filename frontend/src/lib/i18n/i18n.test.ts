import { describe, expect, it } from "vitest";
import { en } from "./en";
import { hi } from "./hi";
import { auditActionLabel, auditEntityLabel, plural, roleLabel, translate, translateOr, type Translate } from "./index";

const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

describe("dictionaries", () => {
  it("has every English key in Hindi", () => {
    const missing = Object.keys(en).filter((key) => !(key in hi));
    expect(missing).toEqual([]);
  });

  it("has no Hindi keys that English lacks", () => {
    const extra = Object.keys(hi).filter((key) => !(key in en));
    expect(extra).toEqual([]);
  });

  it("has no empty translations", () => {
    for (const [key, value] of Object.entries(hi)) {
      expect(value.trim(), key).not.toBe("");
    }
  });

  it("uses the same placeholders in both languages", () => {
    for (const key of Object.keys(en) as (keyof typeof en)[]) {
      expect(placeholders(hi[key]), key).toEqual(placeholders(en[key]));
    }
  });

  it("covers the shell, nav, login and signup strings", () => {
    for (const prefix of ["shell.", "nav.", "login.", "signup."]) {
      expect(Object.keys(en).some((key) => key.startsWith(prefix)), prefix).toBe(true);
    }
  });
});

describe("translate", () => {
  it("looks up and interpolates", () => {
    expect(translate("en", "dashboard.greeting.morning", { name: "Asha" })).toBe("Good morning, Asha");
    expect(translate("hi", "dashboard.greeting.morning", { name: "Asha" })).toBe("सुप्रभात, Asha");
    expect(translate("en", "common.showing", { shown: 2, total: 5 })).toBe("Showing 2 of 5");
  });

  it("leaves unknown placeholders untouched", () => {
    expect(translate("en", "dashboard.trial.endsOn")).toBe("Your free trial ends on {date}.");
  });

  it("translates dynamic keys with a fallback", () => {
    const t: Translate = (key, vars) => translate("hi", key, vars);
    expect(translateOr(t, "board.CBSE", "CBSE")).toBe("सीबीएसई");
    expect(translateOr(t, "board.UNKNOWN", "UNKNOWN")).toBe("UNKNOWN");
    expect(roleLabel(t, "TEACHER")).toBe("शिक्षक");
    expect(roleLabel(t, "LIBRARIAN", "Librarian")).toBe("Librarian");
  });

  it("picks singular and plural forms", () => {
    const t: Translate = (key, vars) => translate("en", key, vars);
    expect(plural(t, "users.eyebrow", 1)).toBe("1 person can sign in");
    expect(plural(t, "users.eyebrow", 3)).toBe("3 people can sign in");
    expect(plural(t, "dashboard.trial.daysLeft", 1)).toBe("1 day left");
    expect(plural(t, "dashboard.trial.daysLeft", 14)).toBe("14 days left");
  });

  it("has both forms for every plural message", () => {
    for (const key of Object.keys(en)) {
      if (key.endsWith(".one")) expect(Object.keys(en), key).toContain(key.replace(/\.one$/, ".other"));
    }
  });
});

describe("audit labels", () => {
  const t: Translate = (key, vars) => translate("en", key, vars);

  it("names known actions and entities in plain words", () => {
    expect(auditActionLabel(t, "user.created")).toBe("Person added");
    expect(auditEntityLabel(t, "school")).toBe("School");
  });

  it("shows unknown actions as the server sent them", () => {
    expect(auditActionLabel(t, "fees.refunded")).toBe("fees.refunded");
  });
});
