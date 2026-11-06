import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import en from "./en.json";
import ar from "./ar.json";

const STORAGE_KEY = "vat.locale";

function storedLocale(): string {
  try {
    return window.localStorage.getItem(STORAGE_KEY) ?? "en";
  } catch {
    return "en";
  }
}

/** Keeps the document language and direction in step with the UI language (NFR-11). */
export function applyDirection(locale: string): void {
  document.documentElement.lang = locale;
  document.documentElement.dir = locale === "ar" ? "rtl" : "ltr";
}

export function setLocale(locale: "en" | "ar"): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, locale);
  } catch {
    // Storage may be blocked; the choice then lasts only for this page.
  }
  void i18n.changeLanguage(locale);
}

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, ar: { translation: ar } },
  lng: storedLocale(),
  fallbackLng: "en",
  interpolation: { escapeValue: false },
});
applyDirection(i18n.language);
i18n.on("languageChanged", applyDirection);

/** Numbers stay in Latin digits in both languages so tax figures match EmaraTax and source documents. */
export function formatAed(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === "") return "–";
  const n = typeof value === "string" ? Number(value) : value;
  return new Intl.NumberFormat("en-AE", { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n);
}

export function formatDate(value: string | null | undefined): string {
  return value ? value.slice(0, 10) : "–";
}

export default i18n;
