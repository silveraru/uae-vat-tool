import { NavLink, Outlet } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { setLocale } from "../i18n";
import { userManager } from "../auth/auth";

export function Layout() {
  const { t, i18n } = useTranslation();
  return (
    <>
      <a className="skip-link" href="#main">{t("app.skipToContent")}</a>
      <header className="topbar">
        <span className="brand">{t("app.title")}</span>
        <nav aria-label="Main">
          <NavLink to="/" end>{t("nav.portfolio")}</NavLink>
        </nav>
        <button
          type="button"
          onClick={() => setLocale(i18n.language === "ar" ? "en" : "ar")}
          lang={i18n.language === "ar" ? "en" : "ar"}
        >
          {t("nav.language")}
        </button>
        <button type="button" onClick={() => void userManager.signoutRedirect()}>
          {t("nav.signOut")}
        </button>
      </header>
      <main id="main" tabIndex={-1}>
        <Outlet />
      </main>
    </>
  );
}
