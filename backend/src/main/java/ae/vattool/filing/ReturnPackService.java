package ae.vattool.filing;

import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.reconciliation.CertificateService;
import ae.vattool.returns.BoxCalculator.BoxValue;
import ae.vattool.returns.ReturnService;
import ae.vattool.returns.ReturnVersionView;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * The return pack for filing (FIL-01): VAT201 values, a box-by-box EmaraTax entry guide, the
 * reconciliation certificate and the approvals. While the rule pack is unsigned, every format carries
 * the not-for-filing watermark. The PDF is English only for now, since the entry guide is what an
 * agent reads while keying into EmaraTax in English; Arabic PDF rendering needs a bundled font.
 */
@Service
public class ReturnPackService {

    public static final String WATERMARK = "DRAFT RULE PACK: NOT FOR FILING";
    private static final int RETENTION_YEARS = 5;

    public record Pack(UUID id, UUID periodId, UUID versionId, boolean draftRules, String pdfKey, String xlsxKey, String jsonKey,
                       String packSha256, String auditHead, String createdBy, OffsetDateTime createdAt) {
        static final String SELECT = """
                SELECT id, period_id, version_id, draft_rules, pdf_key, xlsx_key, json_key, pack_sha256, audit_head, created_by, created_at
                FROM return_pack
                """;
    }

    public record Download(String fileName, String contentType, byte[] content) {}

    private record Approval(String step, String userName, String email, String note, OffsetDateTime signedAt) {}

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

        byte[] json = toJson(entity, period, version, definition, boxes, certificate, approvals, draft, head);
        byte[] xlsx = toXlsx(entity, period, version, definition, boxes, certificate, approvals, draft);
        byte[] pdf = toPdf(entity, period, version, definition, boxes, certificate, approvals, draft, head);

        UUID id = UUID.randomUUID();
        String prefix = "t/" + RequestContext.requireTenant() + "/packs/" + periodId + "/" + id + "/";
        LocalDate until = LocalDate.now().plusYears(RETENTION_YEARS);
        vault.put(prefix + "return-pack.json", json, "application/json", until);
        vault.put(prefix + "return-pack.xlsx", xlsx, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", until);
        vault.put(prefix + "return-pack.pdf", pdf, "application/pdf", until);
        String hash = VaultStorage.sha256(VaultStorage.sha256(json) + VaultStorage.sha256(xlsx) + VaultStorage.sha256(pdf));
        jdbc.sql("""
                INSERT INTO return_pack (id, tenant_id, entity_id, period_id, version_id, draft_rules, pdf_key, xlsx_key, json_key, pack_sha256, audit_head, created_by)
                VALUES (:id, :tenant, :entity, :period, :version, :draft, :pdf, :xlsx, :json, :hash, :head, :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", period.entityId()).param("period", periodId)
                .param("version", version.id()).param("draft", draft).param("pdf", prefix + "return-pack.pdf")
                .param("xlsx", prefix + "return-pack.xlsx").param("json", prefix + "return-pack.json").param("hash", hash).param("head", head)
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
            default -> throw DomainException.invalid("FORMAT_INVALID", "Format must be pdf, xlsx or json");
        };
    }

    private static byte[] toJson(LegalEntity e, TaxPeriod p, ReturnVersionView v, RulePackDefinition def, Map<String, BoxValue> boxes,
                                 CertificateService.Certificate cert, List<Approval> approvals, boolean draft, String head) {
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

    private static byte[] toXlsx(LegalEntity e, TaxPeriod p, ReturnVersionView v, RulePackDefinition def, Map<String, BoxValue> boxes,
                                 CertificateService.Certificate cert, List<Approval> approvals, boolean draft) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFCellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            XSSFCellStyle warning = wb.createCellStyle();
            Font red = wb.createFont();
            red.setBold(true);
            red.setColor(org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex());
            warning.setFont(red);
            XSSFCellStyle number = wb.createCellStyle();
            number.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

            Sheet s = wb.createSheet("VAT201");
            int r = 0;
            if (draft) {
                Row w = s.createRow(r++);
                w.createCell(0).setCellValue(WATERMARK);
                w.getCell(0).setCellStyle(warning);
            }
            s.createRow(r++).createCell(0).setCellValue(e.legalName() + "  TRN " + e.trn());
            s.createRow(r++).createCell(0).setCellValue("Period " + p.periodStart() + " to " + p.periodEnd() + ", due " + p.dueDate() + ", version " + v.versionNo());
            r++;
            Row head = s.createRow(r++);
            String[] titles = {"Box", "Description", "Amount (AED)", "VAT (AED)", "Adjustment (AED)", "How to enter in EmaraTax"};
            for (int i = 0; i < titles.length; i++) {
                head.createCell(i).setCellValue(titles[i]);
                head.getCell(i).setCellStyle(bold);
            }
            for (RulePackDefinition.Box b : def.form().boxes()) {
                Row row = s.createRow(r++);
                BoxValue value = boxes.get(b.code());
                row.createCell(0).setCellValue(b.code());
                row.createCell(1).setCellValue(b.label().get("en"));
                int col = 2;
                for (String c : List.of("amount", "vat", "adjustment")) {
                    if (b.columns().contains(c)) {
                        row.createCell(col).setCellValue(column(value, c).doubleValue());
                        row.getCell(col).setCellStyle(number);
                    }
                    col++;
                }
                row.createCell(5).setCellValue(b.entryGuide().get("en"));
            }
            for (int i = 0; i < 6; i++) {
                s.setColumnWidth(i, i == 1 || i == 5 ? 60 * 256 : 18 * 256);
            }

            Sheet c = wb.createSheet("Reconciliation");
            int cr = 0;
            if (draft) {
                c.createRow(cr++).createCell(0).setCellValue(WATERMARK);
            }
            c.createRow(cr++).createCell(0).setCellValue("Conclusion: " + cert.conclusion());
            Row ch = c.createRow(cr++);
            String[] ct = {"Check", "Name", "Sets", "Severity", "Open", "Resolved", "Waived"};
            for (int i = 0; i < ct.length; i++) {
                ch.createCell(i).setCellValue(ct[i]);
                ch.getCell(i).setCellStyle(bold);
            }
            for (CertificateService.RuleResult rule : cert.rules()) {
                Row row = c.createRow(cr++);
                row.createCell(0).setCellValue(rule.ruleId());
                row.createCell(1).setCellValue(rule.name());
                row.createCell(2).setCellValue(rule.sets());
                row.createCell(3).setCellValue(rule.severity());
                row.createCell(4).setCellValue(rule.open());
                row.createCell(5).setCellValue(rule.resolved());
                row.createCell(6).setCellValue(rule.waived());
            }
            c.setColumnWidth(1, 70 * 256);

            Sheet a = wb.createSheet("Approvals");
            int ar = 0;
            if (draft) {
                a.createRow(ar++).createCell(0).setCellValue(WATERMARK);
            }
            for (Approval ap : approvals) {
                Row row = a.createRow(ar++);
                row.createCell(0).setCellValue(ap.step());
                row.createCell(1).setCellValue(ap.userName());
                row.createCell(2).setCellValue(ap.email());
                row.createCell(3).setCellValue(String.valueOf(ap.signedAt()));
                row.createCell(4).setCellValue(ap.note() == null ? "" : ap.note());
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not build the workbook", ex);
        }
    }

    private static String esc(Object o) {
        return HtmlUtils.htmlEscape(String.valueOf(o), StandardCharsets.UTF_8.name());
    }

    private static String aed(BigDecimal b) {
        return String.format("%,.2f", b);
    }

    private static byte[] toPdf(LegalEntity e, TaxPeriod p, ReturnVersionView v, RulePackDefinition def, Map<String, BoxValue> boxes,
                                CertificateService.Certificate cert, List<Approval> approvals, boolean draft, String head) {
        StringBuilder h = new StringBuilder();
        // A page margin box repeats on every page, which a fixed element does not reliably do.
        String topMargin = draft
                ? "@top-center { content: \"" + WATERMARK + "\"; color: #b00000; font-weight: bold; font-size: 11pt; }"
                : "";
        h.append("""
                <!DOCTYPE html><html><head><meta charset="UTF-8"/><title>VAT201 return pack</title><style>
                @page { size: A4; margin: 18mm 14mm; TOP_MARGIN @bottom-center { content: "Page " counter(page) " of " counter(pages); font-size: 8pt; color: #555; } }
                body { font-family: Helvetica, Arial, sans-serif; font-size: 9pt; color: #1a2030; }
                h1 { font-size: 16pt; margin: 0 0 4px 0; } h2 { font-size: 11pt; margin: 16px 0 6px 0; border-bottom: 1px solid #999; padding-bottom: 2px; }
                table { width: 100%; border-collapse: collapse; } th, td { border-bottom: 1px solid #ccc; padding: 3px 5px; text-align: left; vertical-align: top; }
                th { background: #eee; font-size: 8pt; } td.n, th.n { text-align: right; white-space: nowrap; } tr.total td { font-weight: bold; background: #f4f4f4; }
                .meta { color: #444; } .hash { font-family: monospace; font-size: 7pt; word-break: break-all; }
                .banner { border: 2px solid #b00; color: #b00; font-weight: bold; padding: 6px; margin-bottom: 8px; text-align: center; }
                .wm { position: fixed; top: 110mm; left: -10mm; width: 200mm; text-align: center; font-size: 34pt; font-weight: bold; color: #e8b4b4; transform: rotate(-35deg); z-index: -1; }
                .ok { color: #17693b; font-weight: bold; } .bad { color: #a4201f; font-weight: bold; }
                </style></head><body>
                """.replace("TOP_MARGIN", topMargin));
        if (draft) {
            h.append("<div class=\"wm\">").append(WATERMARK).append("</div>");
            h.append("<div class=\"banner\">").append(WATERMARK).append(". The rule pack has not been signed off by the tax team.</div>");
        }
        h.append("<h1>VAT201 return pack</h1><p class=\"meta\">").append(esc(e.legalName())).append(" &#183; TRN ").append(esc(e.trn()))
                .append("<br/>Tax period ").append(p.periodStart()).append(" to ").append(p.periodEnd()).append(" &#183; Due ").append(p.dueDate())
                .append("<br/>Return version ").append(v.versionNo()).append(" &#183; Form ").append(esc(v.formVersion()))
                .append(" &#183; Rule pack ").append(esc(v.rulePackVersion())).append("</p>");

        h.append("<h2>VAT201 values and how to enter them</h2><table><tr><th>Box</th><th>Description</th><th class=\"n\">Amount (AED)</th><th class=\"n\">VAT (AED)</th><th class=\"n\">Adjustment (AED)</th><th>EmaraTax</th></tr>");
        for (RulePackDefinition.Box b : def.form().boxes()) {
            BoxValue value = boxes.get(b.code());
            h.append(b.isComputed() ? "<tr class=\"total\">" : "<tr>").append("<td>").append(esc(b.code())).append("</td><td>").append(esc(b.label().get("en"))).append("</td>");
            for (String c : List.of("amount", "vat", "adjustment")) {
                h.append("<td class=\"n\">").append(b.columns().contains(c) ? aed(column(value, c)) : "").append("</td>");
            }
            h.append("<td>").append(esc(b.entryGuide().get("en"))).append("</td></tr>");
        }
        h.append("</table>");

        h.append("<h2>Reconciliation certificate</h2><p>Conclusion: <span class=\"")
                .append("NOT_RECONCILED".equals(cert.conclusion()) ? "bad" : "ok").append("\">").append(esc(cert.conclusion().replace('_', ' ')))
                .append("</span></p>");
        CertificateService.Corner5View c5 = cert.corner5();
        h.append("<p>E-invoices issued in the period: ").append(c5.issuedInvoices()).append(", reported to the FTA: ").append(c5.reportedToFta())
                .append(", not reported: ").append(c5.notReported()).append(", rejected: ").append(c5.rejected())
                .append(".<br/>VAT per reported e-invoices: AED ").append(aed(c5.einvoiceVat())).append("; matched ERP VAT: AED ").append(aed(c5.matchedErpVat()))
                .append("; variance: AED ").append(aed(c5.vatVariance())).append(".</p>");
        h.append("<table><tr><th>Check</th><th>What it proves</th><th>Sets</th><th>Severity</th><th class=\"n\">Open</th><th class=\"n\">Resolved</th><th class=\"n\">Waived</th></tr>");
        for (CertificateService.RuleResult r : cert.rules()) {
            h.append("<tr><td>").append(esc(r.ruleId())).append("</td><td>").append(esc(r.name())).append("</td><td>").append(esc(r.sets()))
                    .append("</td><td>").append(esc(r.severity())).append("</td><td class=\"n\">").append(r.open()).append("</td><td class=\"n\">")
                    .append(r.resolved()).append("</td><td class=\"n\">").append(r.waived()).append("</td></tr>");
        }
        h.append("</table>");
        if (!cert.waivers().isEmpty()) {
            h.append("<h2>Waivers</h2><table><tr><th>Check</th><th>Issue</th><th>Reason</th><th>Waived at</th></tr>");
            for (CertificateService.Waiver w : cert.waivers()) {
                h.append("<tr><td>").append(esc(w.ruleId())).append("</td><td>").append(esc(w.message())).append("</td><td>").append(esc(w.reason()))
                        .append("</td><td>").append(esc(w.waivedAt())).append("</td></tr>");
            }
            h.append("</table>");
        }

        h.append("<h2>Approvals</h2><table><tr><th>Step</th><th>Name</th><th>Email</th><th>Signed at</th><th>Note</th></tr>");
        for (Approval a : approvals) {
            h.append("<tr><td>").append(esc(a.step())).append("</td><td>").append(esc(a.userName())).append("</td><td>").append(esc(a.email()))
                    .append("</td><td>").append(esc(a.signedAt())).append("</td><td>").append(esc(a.note() == null ? "" : a.note())).append("</td></tr>");
        }
        h.append("</table>");
        h.append("<h2>Integrity</h2><p class=\"hash\">Return values hash: ").append(esc(v.valuesHash())).append("<br/>Audit chain head when this pack was made: ")
                .append(esc(head)).append("</p></body></html>");

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(h.toString(), null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not build the PDF", ex);
        }
    }
}
