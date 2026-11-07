import { NavLink, Outlet } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { setLocale } from "../i18n";
import { userManager } from "../auth/auth";
import { Badge, useMe, useRoles } from "../components/ui";

export function Layout() {
  const { t, i18n } = useTranslation();
  const me = useMe();
  const roles = useRoles();
  const firstRole = me.data?.grants[0]?.role;
  return (
    <>
      <a className="skip-link" href="#main">{t("app.skipToContent")}</a>
      <header className="topbar">
        <span className="brand">{t("app.title")}</span>
        <nav aria-label={t("nav.main")}>
          <NavLink to="/" end>{t("nav.portfolio")}</NavLink>
          <NavLink to="/deadlines">{t("nav.deadlines")}</NavLink>
          {roles.canManagePractice && <NavLink to="/practice">{t("nav.practice")}</NavLink>}
          {roles.isAdmin && <NavLink to="/rule-packs">{t("nav.rulePacks")}</NavLink>}
          {roles.isAdmin && <NavLink to="/audit">{t("nav.audit")}</NavLink>}
        </nav>
        {me.data && (
          <span className="muted" data-testid="who">
            {me.data.displayName} <Badge value={firstRole} group="role" />
          </span>
        )}
        <button type="button" onClick={() => setLocale(i18n.language === "ar" ? "en" : "ar")} lang={i18n.language === "ar" ? "en" : "ar"}>
          {t("nav.language")}
        </button>
        <button type="button" onClick={() => void userManager.signoutRedirect()}>{t("nav.signOut")}</button>
      </header>
      <main id="main" tabIndex={-1}>
        <Outlet />
      </main>
    </>
  );
}
