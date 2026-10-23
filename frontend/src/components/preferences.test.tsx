import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthLayout } from "@/components/auth-layout";
import { LanguageSelect } from "@/components/preferences";
import { applyAccountLang, hasDeviceLang, LANG_STORAGE_KEY, localeFor, setLang } from "@/lib/i18n";

const saveAccountLanguage = vi.fn(async (lang: string) => {
  void lang;
});
vi.mock("@/lib/auth/session-store", () => ({ saveAccountLanguage: (lang: string) => saveAccountLanguage(lang) }));

afterEach(() => {
  setLang("en");
  window.localStorage.clear();
  saveAccountLanguage.mockClear();
});

describe("LanguageSelect", () => {
  it("offers English, Hindi and Telugu, each labelled in its own script", () => {
    render(<LanguageSelect />);
    const options = screen.getAllByRole("option") as HTMLOptionElement[];
    expect(options.map((o) => [o.value, o.textContent, o.lang])).toEqual([
      ["en", "EN", "en"],
      ["hi", "हिन्दी", "hi"],
      ["te", "తెలుగు", "te"],
    ]);
  });

  it("switches to Telugu, remembers it on this device and saves it to the account", () => {
    render(<LanguageSelect />);
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "te" } });
    expect(document.documentElement.lang).toBe("te");
    expect(window.localStorage.getItem(LANG_STORAGE_KEY)).toBe("te");
    expect(saveAccountLanguage).toHaveBeenCalledWith("te");
    expect((screen.getByRole("combobox") as HTMLSelectElement).value).toBe("te");
  });

  it("lets a language chosen on the device win over the account's saved language", () => {
    window.localStorage.clear();
    expect(hasDeviceLang()).toBe(false);
    applyAccountLang("te");
    expect(document.documentElement.lang).toBe("te");
    expect(window.localStorage.getItem(LANG_STORAGE_KEY)).toBeNull();
    setLang("hi");
    applyAccountLang("te");
    expect(document.documentElement.lang).toBe("hi");
  });

  it("formats Telugu numbers with te-IN and Indian digit grouping", () => {
    expect(localeFor("te")).toBe("te-IN");
    expect(new Intl.NumberFormat(localeFor("te")).format(1234567)).toBe("12,34,567");
  });
});

describe("a Telugu page on a 390 px phone", () => {
  it("renders the sign-in shell in Telugu without anything wider than the screen", () => {
    window.innerWidth = 390;
    setLang("te");
    const { container } = render(
      <AuthLayout title="స్వాగతం" body="పాఠశాల">
        <p>లాగిన్</p>
      </AuthLayout>,
    );
    expect(screen.getByText("3")).toBeTruthy();
    expect((screen.getByRole("combobox") as HTMLSelectElement).value).toBe("te");
    for (const element of Array.from(container.querySelectorAll<HTMLElement>("*"))) {
      expect(element.scrollWidth).toBeLessThanOrEqual(390);
    }
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(390);
  });
});
