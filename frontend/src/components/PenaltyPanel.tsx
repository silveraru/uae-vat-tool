import { useTranslation } from "react-i18next";
import type { PenaltyEstimate } from "../api/types";
import { Money } from "./ui";

/** An estimate for a reviewer. It says so, names its source, and refuses to guess with no schedule. */
export function PenaltyPanel({ estimate }: { estimate: PenaltyEstimate }) {
  const { t } = useTranslation();
  if (!estimate.scheduleFound) return <div className="alert warn" role="status">{t("penalty.noSchedule")}</div>;
  return (
    <>
      <p className="muted">{t("penalty.note", { source: estimate.source, date: estimate.asOf })}</p>
      {estimate.items.length === 0 ? <p>{t("penalty.none")}</p> : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t("penalty.item")}</th><th className="num">{t("penalty.months")}</th><th className="num">{t("return.amount")}</th></tr></thead>
            <tbody>
              {estimate.items.map((i) => (
                <tr key={i.code}>
                  <td>{t(`penalty.code.${i.code}`)}{i.capped && <> <small className="muted">{t("penalty.capped")}</small></>}</td>
                  <td className="num">{i.months > 0 ? i.months : "–"}</td>
                  <td className="num"><Money value={i.amount} /></td>
                </tr>
              ))}
              <tr className="total"><td>{t("penalty.total")}</td><td /><td className="num"><Money value={estimate.total} /></td></tr>
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
