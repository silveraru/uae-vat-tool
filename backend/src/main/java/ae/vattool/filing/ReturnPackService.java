package ae.vattool.filing;

import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import static ae.vattool.platform.documents.PdfRenderer.aed;
import static ae.vattool.platform.documents.PdfRenderer.esc;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.documents.DocText;
import ae.vattool.platform.documents.PdfRenderer;
import ae.vattool.platform.documents.XlsxBuilder;
import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.reconciliation.CertificateService;
import ae.vattool.returns.BoxCalculator.BoxValue;
import ae.vattool.returns.ReturnService;
import ae.vattool.returns.ReturnVersionView;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The return pack for filing (FIL-01): VAT201 values, a box-by-box EmaraTax entry guide, the
 * reconciliation certificate and the approvals. While the rule pack is unsigned, every format carries
 * the not-for-filing watermark. The PDF and workbook come in English and Arabic.
 */
@Service
public class ReturnPackService {

    public static final String WATERMARK = PdfRenderer.WATERMARK;
    private static final int RETENTION_YEARS = 5;

    public record Pack(UUID id, UUID periodId, UUID versionId, boolean draftRules, String pdfKey, String xlsxKey, String jsonKey,
                       String pdfArKey, String xlsxArKey, String packSha256, String auditHead, String createdBy, OffsetDateTime createdAt) {
        static final String SELECT = """
                SELECT id, period_id, version_id, draft_rules, pdf_key, xlsx_key, json_key, pdf_ar_key, xlsx_ar_key, pack_sha256, audit_head, created_by, created_at
                FROM return_pack
                """;
    }

    public record Download(String fileName, String contentType, byte[] content) {}

    private record Approval(String step, String userName, String email, String note, OffsetDateTime signedAt) {}

    private record Signature(String typedName, String signerEmail, OffsetDateTime signedAt, String signatureHash) {}

    private record MarginRow(String docNumber, String category, BigDecimal purchasePriceAed, BigDecimal acquisitionCostsAed, BigDecimal sellingPriceAed,
                             BigDecimal marginAed, BigDecimal vatAed) {}

    private final JdbcClient jdbc;
    private final PeriodCalendar calendar;
    private final ReturnService returns;
    private final RulePackStore packs;
    private final CertificateService certificates;
    private final VaultStorage vault;
    private final AuditLog audit;

    public ReturnPackService(JdbcClient jdbc, PeriodCalendar calendar, ReturnService returns, RulePackStore packs,
                             CertificateService certificates, VaultStorage vault, AuditLog audit) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.returns = returns;
        this.packs = packs;
        this.certificates = certificates;
        this.vault = vault;
        this.audit = audit;
    }

    @Transactional
    public Pack generate(UUID periodId) {
        TaxPeriod period = calendar.get(periodId);
        if (!period.isLocked()) {
            throw DomainException.conflict("PERIOD_NOT_LOCKED", "A return pack is produced from an approved, locked return");
        }
        ReturnVersionView version = returns.find(period.lockedVersionId()).orElseThrow();
        LegalEntity entity = jdbc.sql(LegalEntity.SELECT + " WHERE id = ?").param(period.entityId()).query(LegalEntity.class).single();
        RulePackDefinition definition = packs.definition(version.rulePackId());
        boolean draft = !packs.isSignedOff(version.rulePackId());
        Map<String, BoxValue> boxes = ReturnService.parseValues(version.boxValues());
        CertificateService.Certificate certificate = certificates.build(period);
        List<Approval> approvals = jdbc.sql("""
                SELECT s.step, u.display_name AS user_name, u.email, s.note, s.signed_at
                FROM signoff s JOIN app_user u ON u.id = s.user_id
                WHERE s.period_id = ? AND s.invalidated_at IS NULL ORDER BY s.signed_at
                """).param(periodId).query(Approval.class).list();
        String head = jdbc.sql("SELECT last_hash FROM audit_chain_head WHERE tenant_id = ?").param(RequestContext.requireTenant())
                .query(String.class).optional().orElse("GENESIS");

        // Only a signature on the figures being packed counts, whatever was signed before.
        List<Signature> signatures = jdbc.sql("""
                SELECT typed_name, signer_email, signed_at, signature_hash FROM esignature WHERE period_id = ? AND values_hash = ? ORDER BY signed_at
                """).params(periodId, version.valuesHash()).query(Signature.class).list();
        List<MarginRow> margin = jdbc.sql("""
                SELECT doc_number, category, purchase_price_aed, acquisition_costs_aed, selling_price_aed, margin_aed, vat_aed
                FROM margin_sale WHERE period_id = ? ORDER BY doc_date, doc_number
                """).param(periodId).query(MarginRow.class).list();

        byte[] json = toJson(entity, period, version, definition, boxes, certificate, approvals, signatures, margin, draft, head);
        byte[] xlsx = toXlsx("en", entity, period, version, definition, boxes, certificate, approvals, draft);
        byte[] pdf = toPdf("en", entity, period, version, definition, boxes, certificate, approvals, signatures, margin, draft, head);
        byte[] xlsxAr = toXlsx("ar", entity, period, version, definition, boxes, certificate, approvals, draft);
        byte[] pdfAr = toPdf("ar", entity, period, version, definition, boxes, certificate, approvals, signatures, margin, draft, head);

        UUID id = UUID.randomUUID();
        String prefix = "t/" + RequestContext.requireTenant() + "/packs/" + periodId + "/" + id + "/";
        LocalDate until = LocalDate.now().plusYears(RETENTION_YEARS);
        vault.put(prefix + "return-pack.json", json, "application/json", until);
        vault.put(prefix + "return-pack.xlsx", xlsx, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", until);
        vault.put(prefix + "return-pack.pdf", pdf, "application/pdf", until);
        vault.put(prefix + "return-pack-ar.xlsx", xlsxAr, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", until);
        vault.put(prefix + "return-pack-ar.pdf", pdfAr, "application/pdf", until);
        String hash = VaultStorage.sha256(VaultStorage.sha256(json) + VaultStorage.sha256(xlsx) + VaultStorage.sha256(pdf)
                + VaultStorage.sha256(xlsxAr) + VaultStorage.sha256(pdfAr));
        jdbc.sql("""
                INSERT INTO return_pack (id, tenant_id, entity_id, period_id, version_id, draft_rules, pdf_key, xlsx_key, json_key, pdf_ar_key, xlsx_ar_key, pack_sha256, audit_head, created_by)
                VALUES (:id, :tenant, :entity, :period, :version, :draft, :pdf, :xlsx, :json, :pdfAr, :xlsxAr, :hash, :head, :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", period.entityId()).param("period", periodId)
                .param("version", version.id()).param("draft", draft).param("pdf", prefix + "return-pack.pdf")
                .param("xlsx", prefix + "return-pack.xlsx").param("json", prefix + "return-pack.json")
                .param("pdfAr", prefix + "return-pack-ar.pdf").param("xlsxAr", prefix + "return-pack-ar.xlsx").param("hash", hash).param("head", head)
                .param("by", RequestContext.requireActor().actorId())
                .update();
        audit.record("RETURN_PACK_GENERATED", "RETURN_PACK", id, period.entityId(), null,
                Map.of("version", version.versionNo(), "sha256", hash, "draftRules", draft), null);
        return latest(periodId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Pack> latest(UUID periodId) {
        return jdbc.sql(Pack.SELECT + " WHERE period_id = ? ORDER BY created_at DESC LIMIT 1").param(periodId).query(Pack.class).optional();
    }

    @Transactional(readOnly = true)
    public Download download(UUID periodId, String format) {
        Pack pack = latest(periodId).orElseThrow(() -> DomainException.notFound("Return pack"));
        return switch (format) {
            case "pdf" -> new Download("return-pack.pdf", "application/pdf", vault.get(pack.pdfKey()));
            case "xlsx" -> new Download("return-pack.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", vault.get(pack.xlsxKey()));
            case "json" -> new Download("return-pack.json", "application/json", vault.get(pack.jsonKey()));
            case "pdf-ar" -> new Download("return-pack-ar.pdf", "application/pdf", vault.get(requireArabic(pack.pdfArKey())));
            case "xlsx-ar" -> new Download("return-pack-ar.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", vault.get(requireArabic(pack.xlsxArKey())));
            default -> throw DomainException.invalid("FORMAT_INVALID", "Format must be pdf, xlsx, json, pdf-ar or xlsx-ar");
        };
    }

    /** A pack made before Arabic existed has no Arabic files, and generating it again makes them. */
    private static String requireArabic(String key) {
        if (key == null) {
            throw DomainException.notFound("Arabic return pack");
        }
        return key;
    }

    private static byte[] toJson(LegalEntity e, TaxPeriod p, ReturnVersionView v, RulePackDefinition def, Map<String, BoxValue> boxes,
                                 CertificateService.Certificate cert, List<Approval> approvals, List<Signature> signatures, List<MarginRow> margin,
                                 boolean draft, String head) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (draft) {
            out.put("watermark", WATERMARK);
        }
        out.put("draftRulePack", draft);
        out.put("entity", Map.of("legalName", e.legalName(), "trn", String.valueOf(e.trn())));
        out.put("period", Map.of("start", p.periodStart().toString(), "end", p.periodEnd().toString(), "due", p.dueDate().toString()));
        out.put("return", Map.of("version", v.versionNo(), "valuesHash", v.valuesHash(), "form", v.formCode() + " " + v.formVersion(), "rulePack", v.rulePackVersion()));
        List<Map<String, String>> rows = new ArrayList<>();
        for (RulePackDefinition.Box b : def.form().boxes()) {
            BoxValue value = boxes.get(b.code());
            Map<String, String> row = new LinkedHashMap<>();
            row.put("box", b.code());
            row.put("label", b.label().get("en"));
            for (String c : b.columns()) {
                row.put(c, column(value, c).toPlainString());
            }
            row.put("entryGuide", b.entryGuide().get("en"));
            rows.add(row);
        }
        out.put("boxes", rows);
        out.put("reconciliationCertificate", cert);
        out.put("approvals", approvals);
        out.put("eSignatures", signatures);
        out.put("profitMarginScheme", Map.of("applied", !margin.isEmpty(), "schedule", margin));
        out.put("auditChainHead", head);
        return JsonMapper.builder().build().writeValueAsBytes(out);
    }

    private static BigDecimal column(BoxValue v, String name) {
        return switch (name) {
            case "amount" -> v.amount();
            case "vat" -> v.vat();
            default -> v.adjustment();
        };
    }

    private static byte[] toXlsx(String lang, LegalEntity e, TaxPeriod p, ReturnVersionView v, RulePackDefinition def, Map<String, BoxValue> boxes,
                                 CertificateService.Certificate cert, List<Approval> approvals, boolean draft) {
        DocText t = DocText.of(lang);
        XlsxBuilder x = new XlsxBuilder(lang, draft).sheet("VAT201");
        x.row(e.legalName() + "  " + t.t("doc.trn") + " " + e.trn());
        x.row(t.t("doc.period") + " " + p.periodStart() + " / " + p.periodEnd() + ", " + t.t("doc.due") + " " + p.dueDate() + ", " + t.t("pack.version") + " " + v.versionNo());
        x.blank();
        x.header(t.t("doc.box"), t.t("doc.description"), t.t("pack.amount"), t.t("pack.vat"), t.t("pack.adjustment"), t.t("pack.entry"));
        for (RulePackDefinition.Box b : def.form().boxes()) {
            BoxValue value = boxes.get(b.code());
            x.row(b.code(), b.label().getOrDefault(lang, b.label().get("en")),
                    b.columns().contains("amount") ? column(value, "amount") : null,
                    b.columns().contains("vat") ? column(value, "vat") : null,
                    b.columns().contains("adjustment") ? column(value, "adjustment") : null,
                    b.entryGuide().getOrDefault(lang, b.entryGuide().get("en")));
        }
        x.widths(18, 60, 18, 18, 18, 60);
        x.sheet(t.t("pack.reconSheet"));
        x.row(t.t("pack.conclusion") + ": " + t.t("pack.conclusion." + cert.conclusion()));
        x.header(t.t("pack.check"), t.t("doc.description"), t.t("pack.sets"), t.t("pack.severity"), t.t("pack.open"), t.t("pack.resolved"), t.t("pack.waived"));
        for (CertificateService.RuleResult rule : cert.rules()) {
            x.row(rule.ruleId(), ruleName(t, rule), rule.sets(), t.t("pack.severity." + rule.severity()), rule.open(), rule.resolved(), rule.waived());
        }
        x.widths(10, 70, 10, 12, 8, 10, 8);
        x.sheet(t.t("pack.approvalsSheet"));
        for (Approval a : approvals) {
            x.row(t.t("pack.step." + a.step()), a.userName(), a.email(), String.valueOf(a.signedAt()), a.note() == null ? "" : a.note());
        }
        return x.build();
    }

    private static String ruleName(DocText t, CertificateService.RuleResult rule) {
        return "en".equals(t.lang()) || !t.has("rule." + rule.ruleId()) ? rule.name() : t.t("rule." + rule.ruleId());
    }

    /** The PDF's base font has no arrow glyphs, so the data sets are written out for it. */
    private static String plainSets(String sets) {
        return sets.replace("→", " to ").replace("↔", " and ");
    }

    private static byte[] toPdf(String lang, LegalEntity e, TaxPeriod p, ReturnVersionView v, RulePackDefinition def, Map<String, BoxValue> boxes,
                                CertificateService.Certificate cert, List<Approval> approvals, List<Signature> signatures, List<MarginRow> margin, boolean draft, String head) {
        DocText t = DocText.of(lang);
        StringBuilder h = new StringBuilder();
        h.append("<h1>").append(esc(t.t("pack.title"))).append("</h1><p class=\"meta\">").append(esc(e.legalName())).append(" &#183; ").append(esc(t.t("doc.trn")))
                .append(" <span class=\"ltr\">").append(esc(e.trn())).append("</span><br/>").append(esc(t.t("pack.taxPeriod"))).append(" <span class=\"ltr\">")
                .append(p.periodStart()).append(" / ").append(p.periodEnd()).append("</span> &#183; ").append(esc(t.t("doc.due"))).append(" <span class=\"ltr\">").append(p.dueDate())
                .append("</span><br/>").append(esc(t.t("pack.returnVersion"))).append(" ").append(v.versionNo()).append(" &#183; ").append(esc(t.t("pack.form"))).append(" ")
                .append(esc(v.formVersion())).append(" &#183; ").append(esc(t.t("pack.rulePack"))).append(" ").append(esc(v.rulePackVersion())).append("</p>");

        h.append("<h2>").append(esc(t.t("pack.valuesHeading"))).append("</h2><table><tr><th>").append(esc(t.t("doc.box"))).append("</th><th>").append(esc(t.t("doc.description")))
                .append("</th><th class=\"n\">").append(esc(t.t("pack.amount"))).append("</th><th class=\"n\">").append(esc(t.t("pack.vat"))).append("</th><th class=\"n\">")
                .append(esc(t.t("pack.adjustment"))).append("</th><th>EmaraTax</th></tr>");
        for (RulePackDefinition.Box b : def.form().boxes()) {
            BoxValue value = boxes.get(b.code());
            h.append(b.isComputed() ? "<tr class=\"total\">" : "<tr>").append("<td>").append(esc(b.code())).append("</td><td>").append(esc(b.label().getOrDefault(lang, b.label().get("en")))).append("</td>");
            for (String c : List.of("amount", "vat", "adjustment")) {
                h.append("<td class=\"n\">").append(b.columns().contains(c) ? aed(column(value, c)) : "").append("</td>");
            }
            h.append("<td>").append(esc(b.entryGuide().getOrDefault(lang, b.entryGuide().get("en")))).append("</td></tr>");
        }
        h.append("</table>");

        h.append("<p>").append(esc(t.t("pack.marginIndicator"))).append(": <strong>").append(esc(t.t(margin.isEmpty() ? "doc.no" : "doc.yes"))).append("</strong></p>");
        if (!margin.isEmpty()) {
            h.append("<h2>").append(esc(t.t("pack.marginSchedule"))).append("</h2><table><tr><th>").append(esc(t.t("doc.document"))).append("</th><th>").append(esc(t.t("pack.marginCategory")))
                    .append("</th><th class=\"n\">").append(esc(t.t("pack.marginPurchase"))).append("</th><th class=\"n\">").append(esc(t.t("pack.marginSelling"))).append("</th><th class=\"n\">")
                    .append(esc(t.t("pack.marginMargin"))).append("</th><th class=\"n\">").append(esc(t.t("pack.vat"))).append("</th></tr>");
            for (MarginRow m : margin) {
                h.append("<tr><td>").append(esc(m.docNumber())).append("</td><td>").append(esc(m.category())).append("</td><td class=\"n\">").append(aed(m.purchasePriceAed().add(m.acquisitionCostsAed())))
                        .append("</td><td class=\"n\">").append(aed(m.sellingPriceAed())).append("</td><td class=\"n\">").append(aed(m.marginAed())).append("</td><td class=\"n\">").append(aed(m.vatAed())).append("</td></tr>");
            }
            h.append("</table>");
        }

        h.append("<h2>").append(esc(t.t("pack.certificate"))).append("</h2><p>").append(esc(t.t("pack.conclusion"))).append(": <span class=\"")
                .append("NOT_RECONCILED".equals(cert.conclusion()) ? "bad" : "ok").append("\">").append(esc(t.t("pack.conclusion." + cert.conclusion()))).append("</span></p>");
        CertificateService.Corner5View c5 = cert.corner5();
        h.append("<p>").append(esc(t.t("pack.corner5", "issued", c5.issuedInvoices(), "reported", c5.reportedToFta(), "notReported", c5.notReported(), "rejected", c5.rejected())))
                .append("<br/>").append(esc(t.t("pack.corner5Vat", "einvoice", aed(c5.einvoiceVat()), "erp", aed(c5.matchedErpVat()), "variance", aed(c5.vatVariance())))).append("</p>");
        h.append("<table><tr><th>").append(esc(t.t("pack.check"))).append("</th><th>").append(esc(t.t("pack.proves"))).append("</th><th>").append(esc(t.t("pack.sets"))).append("</th><th>")
                .append(esc(t.t("pack.severity"))).append("</th><th class=\"n\">").append(esc(t.t("pack.open"))).append("</th><th class=\"n\">").append(esc(t.t("pack.resolved")))
                .append("</th><th class=\"n\">").append(esc(t.t("pack.waived"))).append("</th></tr>");
        for (CertificateService.RuleResult r : cert.rules()) {
            h.append("<tr><td>").append(esc(r.ruleId())).append("</td><td>").append(esc(ruleName(t, r))).append("</td><td class=\"ltr\">").append(esc(plainSets(r.sets())))
                    .append("</td><td>").append(esc(t.t("pack.severity." + r.severity()))).append("</td><td class=\"n\">").append(r.open()).append("</td><td class=\"n\">")
                    .append(r.resolved()).append("</td><td class=\"n\">").append(r.waived()).append("</td></tr>");
        }
        h.append("</table>");
        if (!cert.waivers().isEmpty()) {
            h.append("<h2>").append(esc(t.t("pack.waivers"))).append("</h2><table><tr><th>").append(esc(t.t("pack.check"))).append("</th><th>").append(esc(t.t("pack.issue"))).append("</th><th>")
                    .append(esc(t.t("pack.reason"))).append("</th><th>").append(esc(t.t("pack.waivedAt"))).append("</th></tr>");
            for (CertificateService.Waiver w : cert.waivers()) {
                h.append("<tr><td>").append(esc(w.ruleId())).append("</td><td>").append(esc(w.message())).append("</td><td>").append(esc(w.reason()))
                        .append("</td><td class=\"ltr\">").append(esc(w.waivedAt())).append("</td></tr>");
            }
            h.append("</table>");
        }

        h.append("<h2>").append(esc(t.t("pack.approvals"))).append("</h2><table><tr><th>").append(esc(t.t("pack.stepHeading"))).append("</th><th>").append(esc(t.t("pack.name"))).append("</th><th>")
                .append(esc(t.t("pack.email"))).append("</th><th>").append(esc(t.t("pack.signedAt"))).append("</th><th>").append(esc(t.t("pack.note"))).append("</th></tr>");
        for (Approval a : approvals) {
            h.append("<tr><td>").append(esc(t.t("pack.step." + a.step()))).append("</td><td>").append(esc(a.userName())).append("</td><td class=\"ltr\">").append(esc(a.email()))
                    .append("</td><td class=\"ltr\">").append(esc(a.signedAt())).append("</td><td>").append(esc(a.note() == null ? "" : a.note())).append("</td></tr>");
        }
        h.append("</table>");
        if (!signatures.isEmpty()) {
            h.append("<h2>").append(esc(t.t("pack.esignature"))).append("</h2><table><tr><th>").append(esc(t.t("pack.name"))).append("</th><th>").append(esc(t.t("pack.email"))).append("</th><th>")
                    .append(esc(t.t("pack.signedAt"))).append("</th><th>SHA-256</th></tr>");
            for (Signature s : signatures) {
                h.append("<tr><td>").append(esc(s.typedName())).append("</td><td class=\"ltr\">").append(esc(s.signerEmail())).append("</td><td class=\"ltr\">").append(esc(s.signedAt()))
                        .append("</td><td class=\"hash\">").append(esc(s.signatureHash())).append("</td></tr>");
            }
            h.append("</table>");
        }
        h.append("<h2>").append(esc(t.t("pack.integrity"))).append("</h2><p class=\"hash\">").append(esc(t.t("pack.valuesHash"))).append(": ").append(esc(v.valuesHash())).append("<br/>")
                .append(esc(t.t("pack.auditHead"))).append(": ").append(esc(head)).append("</p>");
        return PdfRenderer.render(t.t("pack.title"), h.toString(), lang, draft);
    }
}
