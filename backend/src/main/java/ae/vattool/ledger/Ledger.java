package ae.vattool.ledger;

import ae.vattool.masterdata.MappingService;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.rules.Classifier;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way into the ledger. Everything that changes tax goes through here, which is what keeps
 * the immutability rule and recompute requests reliable.
 */
@Service
public class Ledger {

    public enum WriteResult { INSERTED, REVISED, UNCHANGED }

    public record Written(WriteResult result, UUID lineId, UUID periodId) {}

    /**
     * Saves database round trips while one file loads, which is what lets a row-by-row load meet the
     * NFR-07 rate. Reference data may be read once per file: a rule pack or mapping published during
     * a load applies from the next load, and each line records the version it used. Locks and new
     * documents last only for one transaction, so newTransaction() must be called at the start of each.
     */
    public static final class WriteCache {
        private final Map<LocalDate, RulePackStore.ActivePack> packs = new HashMap<>();
        private final Map<LocalDate, Optional<MappingService.VersionWithEntries>> mappings = new HashMap<>();
        private final Map<LocalDate, Optional<TaxPeriod>> periods = new HashMap<>();
        private final Map<String, Boolean> designatedZones = new HashMap<>();
        private BigDecimal defaultRecovery;
        private final Set<UUID> sharedPeriods = new HashSet<>();
        private final Set<UUID> requestedPeriods = new HashSet<>();
        private final Set<UUID> newDocuments = new HashSet<>();

        public void newTransaction() {
            sharedPeriods.clear();
            requestedPeriods.clear();
            newDocuments.clear();
        }

        /** A document created in this transaction cannot already have lines, so no lookup is needed. */
        public void documentCreated(UUID id) {
            newDocuments.add(id);
        }
    }

    private final JdbcClient jdbc;
    private final RulePackStore packs;
    private final MappingService mappings;
    private final PeriodCalendar calendar;

    public Ledger(JdbcClient jdbc, RulePackStore packs, MappingService mappings, PeriodCalendar calendar) {
        this.jdbc = jdbc;
        this.packs = packs;
        this.mappings = mappings;
        this.calendar = calendar;
    }

    /**
     * Classifies a source line and writes it. Re-sending the same tax content changes nothing, and
     * a person's reclassification survives such a reload. Changed content appends a new revision.
     * A tax point in a locked period still lands in that period, but only the correction flow can
     * act on it, because the locked return is a stored snapshot that never recomputes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Written writeSourceLine(LineInput in) {
        WriteCache cache = new WriteCache();
        return writeSourceLine(in, cache);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Written writeSourceLine(LineInput in, WriteCache cache) {
        TaxPeriod period = periodFor(in.filerEntityId(), in.taxPoint(), cache)
                .orElseThrow(() -> DomainException.invalid("BEFORE_FIRST_PERIOD",
                        "Tax point " + in.taxPoint() + " is before the entity's first tax period"));

        // Held until commit, so approving this period waits for this write and then sees it. The
        // lock is taken before reading the current line for the same reason.
        lockShared(period.id(), cache);

        String taxHash = taxHash(in);
        Optional<CurrentLine> current = cache.newDocuments.contains(in.documentId())
                ? Optional.empty()
                : currentLine(in.documentId(), in.lineNo());
        if (current.isPresent() && current.get().taxHash().equals(taxHash)) {
            return new Written(WriteResult.UNCHANGED, current.get().id(), period.id());
        }

        RulePackStore.ActivePack pack = cache.packs.computeIfAbsent(in.taxPoint(), packs::forDate);
        Classified c = classify(pack.definition(), in, cache);
        UUID logicalId = current.map(CurrentLine::logicalLineId).orElseGet(UUID::randomUUID);
        int revision = current.map(l -> l.revision() + 1).orElse(1);

        current.ifPresent(l -> supersede(l.id(), l.taxYear(), l.periodId(), cache));

        UUID id = UUID.randomUUID();
        insert(id, logicalId, revision, in, period, c, pack.meta().version(), taxHash);
        requestRecompute(period.id(), cache);
        return new Written(current.isPresent() ? WriteResult.REVISED : WriteResult.INSERTED, id, period.id());
    }

    /** The period for a date, read once per file. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TaxPeriod> periodFor(UUID filerId, LocalDate date, WriteCache cache) {
        return cache.periods.computeIfAbsent(date, d -> calendar.periodFor(filerId, d));
    }

    private void lockShared(UUID periodId, WriteCache cache) {
        if (cache.sharedPeriods.add(periodId)) {
            jdbc.sql("SELECT id FROM tax_period WHERE id = ? FOR SHARE").param(periodId).query(UUID.class).single();
        }
    }

    private void requestRecompute(UUID periodId, WriteCache cache) {
        lockShared(periodId, cache);
        if (cache.requestedPeriods.add(periodId)) {
            jdbc.sql("""
                    INSERT INTO period_recompute_request (period_id, tenant_id) VALUES (?, ?)
                    ON CONFLICT (period_id) DO UPDATE SET requested_at = now()
                    """).params(periodId, RequestContext.requireTenant()).update();
        }
    }

    /** Supersedes one current line with no replacement, for a voided document. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int voidDocumentLine(UUID documentId, int lineNo) {
        Optional<CurrentLine> current = currentLine(documentId, lineNo);
        current.ifPresent(l -> supersede(l.id(), l.taxYear(), l.periodId(), new WriteCache()));
        return current.isPresent() ? 1 : 0;
    }

    /**
     * Appends a revision with a person's chosen treatment (TXC-07). The reason is mandatory, and
     * amounts are unchanged except that reverse charge VAT is recalculated if needed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID reclassify(TaxLine line, String treatment, String blockedCategory, BigDecimal recoveryPct,
                           String reasonCode, String reason) {
        RulePackStore.ActivePack pack = packs.forDate(line.taxPoint());
        RulePackDefinition def = pack.definition();
        BigDecimal vat = Classifier.vatToAccount(def, line.direction(), treatment, line.netAed(), line.vatAed());
        boolean memo = !"CUSTOMS".equals(line.sourceSystem())
                && ("IMPORT".equals(treatment) || "IMPORT_ADJUSTMENT".equals(treatment));
        BigDecimal pct = recoveryPct != null ? recoveryPct : line.recoveryPct();
        BigDecimal recoverable = Classifier.recoverable(line.direction(), treatment, blockedCategory, memo, vat,
                pct == null ? BigDecimal.valueOf(100) : pct);
        int taxYear = line.taxPoint().getYear();
        supersede(line.id(), taxYear, line.periodId(), new WriteCache());
        UUID id = UUID.randomUUID();
        // A person has now decided, so earlier review flags no longer apply to this line.
        jdbc.sql("""
                INSERT INTO tax_line (id, tenant_id, tax_year, logical_line_id, revision, filer_entity_id, member_entity_id,
                    establishment_id, emirate, period_id, document_id, ingest_row_id, line_no, line_kind, adj_box, adjustment_id,
                    source_system, direction, goods_or_services, gl_account, tax_code, description, counterparty_trn,
                    counterparty_country, tax_point, currency, fx_rate, net_doc, vat_doc, net_aed, vat_aed, recoverable_vat_aed,
                    recovery_pct, treatment, blocked_category, drc_category, place_of_supply, memo, review_flags, classified_by,
                    confidence, rule_ids, rule_pack_version, mapping_version_id, change_reason_code, change_reason, changed_by, tax_hash, input_attribution)
                SELECT :id, tenant_id, tax_year, logical_line_id, revision + 1, filer_entity_id, member_entity_id,
                    establishment_id, emirate, period_id, document_id, ingest_row_id, line_no, line_kind, adj_box, adjustment_id,
                    source_system, direction, goods_or_services, gl_account, tax_code, description, counterparty_trn,
                    counterparty_country, tax_point, currency, fx_rate, net_doc, vat_doc, net_aed, :vat, :recoverable,
                    :pct, :treatment, :blocked, drc_category, place_of_supply, :memo, '{}', 'USER',
                    1, rule_ids, :packVersion, mapping_version_id, :reasonCode, :reason, :by, tax_hash,
                    CASE WHEN CAST(:explicitPct AS numeric) IS NULL THEN input_attribution
                         WHEN direction <> 'IN' THEN input_attribution
                         WHEN CAST(:explicitPct AS numeric) = 100 THEN 'TAXABLE'
                         WHEN CAST(:explicitPct AS numeric) = 0 THEN 'EXEMPT'
                         ELSE 'RESIDUAL' END
                FROM tax_line WHERE tenant_id = :tenant AND tax_year = :year AND id = :oldId
                """)
                .param("id", id).param("vat", vat).param("recoverable", recoverable).param("pct", pct)
                .param("treatment", treatment).param("blocked", blockedCategory).param("memo", memo)
                .param("packVersion", pack.meta().version()).param("reasonCode", reasonCode).param("reason", reason)
                .param("by", RequestContext.requireActor().actorId())
                .param("tenant", RequestContext.requireTenant()).param("year", taxYear).param("oldId", line.id())
                .param("explicitPct", recoveryPct)
                .update();
        requestRecompute(line.periodId());
        return id;
    }

    /** Writes a ledger line that has no source document row: a manual journal or system adjustment. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID writeAdjustment(UUID filerId, UUID periodId, LocalDate taxPoint, String adjBox, String direction,
                                BigDecimal netAed, BigDecimal vatAed, UUID adjustmentId, UUID documentId,
                                String description, String reasonCode) {
        UUID id = UUID.randomUUID();
        String version = packs.forDate(taxPoint).meta().version();
        jdbc.sql("""
                INSERT INTO tax_line (id, tenant_id, tax_year, logical_line_id, revision, filer_entity_id, member_entity_id,
                    period_id, document_id, line_no, line_kind, adj_box, adjustment_id, source_system, direction, description,
                    tax_point, currency, fx_rate, net_doc, vat_doc, net_aed, vat_aed, recoverable_vat_aed, treatment,
                    classified_by, confidence, rule_pack_version, change_reason_code, changed_by, tax_hash)
                VALUES (:id, :tenant, :year, :id, 1, :filer, :filer, :period, :document, 1, 'ADJUSTMENT', :box, :adjustment,
                    'MANUAL', :direction, :description, :taxPoint, 'AED', 1, :net, :vat, :net, :vat, 0, 'OUT_OF_SCOPE',
                    'SYSTEM', 1, :version, :reasonCode, :by, :hash)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("year", taxPoint.getYear())
                .param("filer", filerId).param("period", periodId).param("document", documentId)
                .param("box", adjBox).param("adjustment", adjustmentId).param("direction", direction)
                .param("description", description).param("taxPoint", taxPoint).param("net", netAed).param("vat", vatAed)
                .param("version", version).param("reasonCode", reasonCode)
                .param("by", RequestContext.requireActor().actorId()).param("hash", id.toString())
                .update();
        requestRecompute(periodId);
        return id;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requestRecompute(UUID periodId) {
        // Always the period first, then the request row. The recompute job locks in the same order,
        // and a different order here could deadlock a data load against it.
        jdbc.sql("SELECT id FROM tax_period WHERE id = ? FOR SHARE").param(periodId).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO period_recompute_request (period_id, tenant_id) VALUES (?, ?)
                ON CONFLICT (period_id) DO UPDATE SET requested_at = now()
                """).params(periodId, RequestContext.requireTenant()).update();
    }

    @Transactional(readOnly = true)
    public Optional<TaxLine> currentById(UUID id) {
        return jdbc.sql("SELECT " + TaxLine.COLUMNS + " FROM tax_line WHERE id = ? AND superseded_at IS NULL")
                .param(id).query(TaxLineRowMapper.INSTANCE).optional();
    }

    @Transactional(readOnly = true)
    public List<TaxLine> history(UUID logicalLineId) {
        return jdbc.sql("SELECT " + TaxLine.COLUMNS + " FROM tax_line WHERE logical_line_id = ? ORDER BY revision")
                .param(logicalLineId).query(TaxLineRowMapper.INSTANCE).list();
    }

    private record CurrentLine(UUID id, UUID logicalLineId, int revision, int taxYear, UUID periodId, String taxHash) {}

    private Optional<CurrentLine> currentLine(UUID documentId, int lineNo) {
        return jdbc.sql("""
                SELECT id, logical_line_id, revision, tax_year, period_id, tax_hash FROM tax_line
                WHERE document_id = ? AND line_no = ? AND superseded_at IS NULL AND line_kind = 'NORMAL'
                """).params(documentId, lineNo).query(CurrentLine.class).optional();
    }

    private void supersede(UUID id, int taxYear, UUID periodId, WriteCache cache) {
        jdbc.sql("UPDATE tax_line SET superseded_at = now() WHERE tenant_id = ? AND tax_year = ? AND id = ?")
                .params(RequestContext.requireTenant(), taxYear, id).update();
        requestRecompute(periodId, cache);
    }

    private record Classified(Classifier.Outcome outcome, BigDecimal vatAed, BigDecimal recoverable,
                              BigDecimal recoveryPct, UUID mappingVersionId) {}

    private Classified classify(RulePackDefinition def, LineInput in, WriteCache cache) {
        Optional<MappingService.VersionWithEntries> mapping =
                cache.mappings.computeIfAbsent(in.taxPoint(), d -> mappings.forDate(in.filerEntityId(), d));
        Classifier.Mapped mapped = in.forcedTreatment() != null
                ? new Classifier.Mapped(in.forcedTreatment(), null, null)
                : mergeMapping(mapping, in);
        boolean counterpartyDz = in.counterpartyTrn() != null
                && cache.designatedZones.computeIfAbsent(in.counterpartyTrn(), this::counterpartyDesignatedZone);
        Classifier.Outcome outcome = Classifier.classify(def, new Classifier.Facts(
                in.direction(), in.sourceSystem(), in.goodsOrServices(), in.counterpartyCountry(),
                in.counterpartyTrn() != null, counterpartyDz, in.establishmentDesignatedZone(), in.description(),
                in.netAed(), in.vatAed(), mapped));
        BigDecimal vat = Classifier.vatToAccount(def, in.direction(), outcome.treatment(), in.netAed(), in.vatAed());
        if (cache.defaultRecovery == null) {
            cache.defaultRecovery = defaultRecovery(in.filerEntityId());
        }
        BigDecimal pct = in.recoveryPct() != null ? in.recoveryPct() : cache.defaultRecovery;
        BigDecimal recoverable = Classifier.recoverable(in.direction(), outcome.treatment(), outcome.blockedCategory(),
                outcome.memo(), vat, pct);
        return new Classified(outcome, vat, recoverable, pct, mapping.map(m -> m.version().id()).orElse(null));
    }

    /** The tax code decides the treatment. The GL account fills gaps. Vendor and GL can block recovery. */
    private static Classifier.Mapped mergeMapping(Optional<MappingService.VersionWithEntries> mapping, LineInput in) {
        if (mapping.isEmpty()) {
            return Classifier.Mapped.NONE;
        }
        MappingService.VersionWithEntries m = mapping.get();
        Optional<MappingService.Entry> code = m.lookup("TAX_CODE", in.taxCode());
        Optional<MappingService.Entry> gl = m.lookup("GL_ACCOUNT", in.glAccount());
        Optional<MappingService.Entry> vendor = "IN".equals(in.direction())
                ? m.lookup("VENDOR_TRN", in.counterpartyTrn()) : Optional.empty();
        String treatment = firstNonNull(code.map(MappingService.Entry::treatment), gl.map(MappingService.Entry::treatment));
        String blocked = firstNonNull(code.map(MappingService.Entry::blockedCategory),
                gl.map(MappingService.Entry::blockedCategory), vendor.map(MappingService.Entry::blockedCategory));
        String drc = firstNonNull(code.map(MappingService.Entry::drcCategory), gl.map(MappingService.Entry::drcCategory));
        return new Classifier.Mapped(treatment, blocked, drc);
    }

    @SafeVarargs
    private static String firstNonNull(Optional<String>... options) {
        for (Optional<String> o : options) {
            if (o.isPresent()) {
                return o.get();
            }
        }
        return null;
    }

    private boolean counterpartyDesignatedZone(String trn) {
        return jdbc.sql("SELECT designated_zone FROM counterparty WHERE trn = ?").param(trn)
                .query(Boolean.class).optional().orElse(false);
    }

    private BigDecimal defaultRecovery(UUID filerId) {
        return jdbc.sql("SELECT default_recovery_pct FROM legal_entity WHERE id = ?").param(filerId)
                .query(BigDecimal.class).single();
    }

    private void insert(UUID id, UUID logicalId, int revision, LineInput in, TaxPeriod period, Classified c,
                        String packVersion, String taxHash) {
        Classifier.Outcome o = c.outcome();
        jdbc.sql("""
                INSERT INTO tax_line (id, tenant_id, tax_year, logical_line_id, revision, filer_entity_id, member_entity_id,
                    establishment_id, emirate, period_id, document_id, ingest_row_id, line_no, line_kind, source_system, direction,
                    goods_or_services, gl_account, tax_code, description, counterparty_trn, counterparty_country, tax_point,
                    currency, fx_rate, net_doc, vat_doc, net_aed, vat_aed, recoverable_vat_aed, recovery_pct, treatment,
                    blocked_category, drc_category, place_of_supply, memo, review_flags, classified_by, confidence, rule_ids,
                    rule_pack_version, mapping_version_id, changed_by, tax_hash, input_attribution)
                VALUES (:id, :tenant, :year, :logical, :revision, :filer, :member, :establishment, :emirate, :period, :document,
                    :row, :lineNo, 'NORMAL', :source, :direction, :gos, :gl, :taxCode, :description, :trn, :country, :taxPoint,
                    :currency, :fx, :netDoc, :vatDoc, :net, :vat, :recoverable, :pct, :treatment, :blocked, :drc, :pos, :memo,
                    string_to_array(:flags, '|'), :by, :confidence, string_to_array(:rules, '|'), :version, :mapping,
                    :actor, :hash, :attribution)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("year", in.taxPoint().getYear())
                .param("logical", logicalId).param("revision", revision).param("filer", in.filerEntityId())
                .param("member", in.memberEntityId()).param("establishment", in.establishmentId())
                .param("emirate", in.emirate()).param("period", period.id()).param("document", in.documentId())
                .param("row", in.ingestRowId()).param("lineNo", in.lineNo()).param("source", in.sourceSystem())
                .param("direction", in.direction()).param("gos", in.goodsOrServices()).param("gl", in.glAccount())
                .param("taxCode", in.taxCode()).param("description", in.description()).param("trn", in.counterpartyTrn())
                .param("country", in.counterpartyCountry()).param("taxPoint", in.taxPoint())
                .param("currency", in.currency()).param("fx", in.fxRate()).param("netDoc", in.netDoc())
                .param("vatDoc", in.vatDoc()).param("net", in.netAed()).param("vat", c.vatAed())
                .param("recoverable", c.recoverable()).param("pct", c.recoveryPct()).param("treatment", o.treatment())
                .param("blocked", o.blockedCategory()).param("drc", o.drcCategory()).param("pos", o.placeOfSupply())
                .param("memo", o.memo()).param("flags", String.join("|", new HashSet<>(o.flags())))
                .param("by", o.classifiedBy()).param("confidence", o.confidence())
                .param("rules", String.join("|", o.ruleIds())).param("version", packVersion)
                .param("mapping", c.mappingVersionId())
                .param("actor", RequestContext.requireActor().actorId()).param("hash", taxHash)
                .param("attribution", attribution(in.direction(), in.recoveryPct()))
                .update();
    }

    /**
     * Input tax either belongs directly to taxable or exempt supplies, or is residual. A file marks a
     * direct line with a recovery rate of 100 or 0, and leaving the rate blank means residual, which
     * is what apportionment works on.
     */
    static String attribution(String direction, BigDecimal explicitPct) {
        if (!"IN".equals(direction)) {
            return null;
        }
        if (explicitPct != null && explicitPct.compareTo(BigDecimal.valueOf(100)) == 0) {
            return "TAXABLE";
        }
        if (explicitPct != null && explicitPct.signum() == 0) {
            return "EXEMPT";
        }
        return "RESIDUAL";
    }

    /** The content that matters for tax. Payment fields are not in it, so a payment update adds no revision. */
    static String taxHash(LineInput in) {
        String canonical = String.join("|", List.of(
                Objects.toString(in.memberEntityId()), Objects.toString(in.establishmentId()),
                Objects.toString(in.direction()), Objects.toString(in.goodsOrServices()),
                Objects.toString(in.glAccount()), Objects.toString(in.taxCode()), Objects.toString(in.description()),
                Objects.toString(in.counterpartyTrn()), Objects.toString(in.counterpartyCountry()),
                in.taxPoint().toString(), in.currency(), in.fxRate().stripTrailingZeros().toPlainString(),
                in.netDoc().stripTrailingZeros().toPlainString(), in.vatDoc().stripTrailingZeros().toPlainString(),
                in.recoveryPct() == null ? "" : in.recoveryPct().stripTrailingZeros().toPlainString(),
                Objects.toString(in.forcedTreatment())));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
