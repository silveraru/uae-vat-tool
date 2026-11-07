import { describe, expect, it } from "vitest";
import i18n, { formatAed, setLocale } from "./index";
import en from "./en.json";
import ar from "./ar.json";

function keys(obj: object, prefix = ""): string[] {
  return Object.entries(obj).flatMap(([k, v]) =>
    v && typeof v === "object" ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`],
  );
}

const sources = import.meta.glob("../**/*.tsx", { query: "?raw", import: "default", eager: true }) as Record<string, string>;
const serverSources = import.meta.glob("../../../backend/src/main/java/**/*.java", { query: "?raw", import: "default", eager: true }) as Record<string, string>;

/** i18next plural keys are stored with a suffix, so strip it when comparing with the code. */
function withoutPlural(key: string) {
  return key.replace(/_(one|other)$/, "");
}

describe("localisation", () => {
  it("has an Arabic translation for every English key, and no extras", () => {
    const pluralInsensitive = (o: object) => [...new Set(keys(o).map(withoutPlural))].sort();
    expect(pluralInsensitive(ar)).toEqual(pluralInsensitive(en));
  });

  it("has no empty translations", () => {
    for (const dict of [en, ar]) {
      const flat = keys(dict);
      for (const k of flat) {
        const value = k.split(".").reduce((o: any, p) => o[p], dict as any);
        expect(String(value).trim().length, k).toBeGreaterThan(0);
      }
    }
  });

  it("defines every key the code asks for, in both languages", () => {
    const known = new Set(keys(en).map(withoutPlural));
    const missing: string[] = [];
    for (const [file, text] of Object.entries(sources)) {
      for (const m of text.matchAll(/\bt\("([a-zA-Z0-9_.-]+)"/g)) {
        if (!known.has(m[1])) missing.push(`${file}: ${m[1]}`);
      }
    }
    expect(missing).toEqual([]);
  });

  it("has an Arabic message for every error code the server can send", () => {
    const codes = new Set<string>();
    for (const text of Object.values(serverSources)) {
      for (const m of text.matchAll(/DomainException\.(?:invalid|conflict)\(\s*"([A-Z0-9_]+)"/g)) codes.add(m[1]);
      for (const m of text.matchAll(/new DomainException\([^,]*,\s*"([A-Z0-9_]+)"/g)) codes.add(m[1]);
    }
    expect(codes.size).toBeGreaterThan(100);
    const translated = (ar as { errors: Record<string, string> }).errors;
    expect([...codes].filter((c) => !translated[c])).toEqual([]);
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
