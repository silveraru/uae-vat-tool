import { describe, expect, it } from "vitest";
import i18n, { formatAed, setLocale } from "./index";
import en from "./en.json";
import ar from "./ar.json";

function keys(obj: object, prefix = ""): string[] {
  return Object.entries(obj).flatMap(([k, v]) =>
    v && typeof v === "object" ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`],
  );
}

describe("localisation", () => {
  it("has an Arabic translation for every English key, and no extras", () => {
    expect(keys(ar).sort()).toEqual(keys(en).sort());
  });

  it("switches the document to right-to-left for Arabic and back", async () => {
    setLocale("ar");
    await i18n.loadLanguages("ar");
    expect(document.documentElement.dir).toBe("rtl");
    expect(document.documentElement.lang).toBe("ar");
    setLocale("en");
    expect(document.documentElement.dir).toBe("ltr");
  });

  it("formats money with two decimals and Latin digits in either language", () => {
    expect(formatAed("1234567.5")).toBe("1,234,567.50");
    setLocale("ar");
    expect(formatAed(0)).toBe("0.00");
    setLocale("en");
  });

  it("shows a dash for missing amounts rather than a misleading zero", () => {
    expect(formatAed(null)).toBe("–");
  });
});
