package ae.vattool.masterdata;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntityService {

    public record EntityInput(String legalName, String legalNameAr, String trn, String entityKind, UUID groupRepId,
                              String filingFrequency, String stagger, LocalDate firstPeriodStart,
                              LocalDate einvoiceGoLive, BigDecimal defaultRecoveryPct,
                              BigDecimal partnerThresholdAed, Boolean requireReviewer) {}

    public record EstablishmentInput(String code, String name, String emirate, Boolean designatedZone, String zoneName) {}

    public record Establishment(UUID id, UUID entityId, String code, String name, String emirate,
                                boolean designatedZone, String zoneName) {}

    private static final Set<String> KINDS = Set.of("STANDALONE", "GROUP_REPRESENTATIVE", "GROUP_MEMBER");
    private static final Set<String> FREQUENCIES = Set.of("MONTHLY", "QUARTERLY");

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final TrnValidator trnValidator;
    private final PeriodCalendar calendar;

    public EntityService(JdbcClient jdbc, AuditLog audit, TrnValidator trnValidator, PeriodCalendar calendar) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.trnValidator = trnValidator;
        this.calendar = calendar;
    }

    @Transactional
    public LegalEntity create(EntityInput in) {
        validate(in);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO legal_entity (id, tenant_id, legal_name, legal_name_ar, trn, entity_kind, group_rep_id,
                        filing_frequency, stagger, first_period_start, einvoice_go_live, default_recovery_pct,
                        partner_threshold_aed, require_reviewer)
                    VALUES (:id, :tenant, :name, :nameAr, :trn, :kind, :rep, :freq, :stagger, :first, :goLive,
                        :recovery, :threshold, :reviewer)
                    """)
                    .param("id", id).param("tenant", RequestContext.requireTenant())
                    .param("name", in.legalName()).param("nameAr", in.legalNameAr()).param("trn", in.trn())
                    .param("kind", in.entityKind()).param("rep", in.groupRepId())
                    .param("freq", in.filingFrequency()).param("stagger", in.stagger())
                    .param("first", in.firstPeriodStart()).param("goLive", in.einvoiceGoLive())
                    .param("recovery", in.defaultRecoveryPct() == null ? new BigDecimal("100") : in.defaultRecoveryPct())
                    .param("threshold", in.partnerThresholdAed())
                    .param("reviewer", in.requireReviewer() == null || in.requireReviewer())
                    .update();
        } catch (DuplicateKeyException e) {
            throw DomainException.conflict("TRN_ALREADY_REGISTERED", "An entity with this TRN already exists");
        }
        LegalEntity created = get(id);
        if (created.isFiler()) {
            calendar.periodFor(id, created.firstPeriodStart());
        }
        audit.record("ENTITY_CREATED", "LEGAL_ENTITY", id, id, null, created, null);
        return created;
    }

    private void validate(EntityInput in) {
        if (in.legalName() == null || in.legalName().isBlank()) {
            throw DomainException.invalid("LEGAL_NAME_REQUIRED", "Legal name is required");
        }
        if (in.entityKind() == null || !KINDS.contains(in.entityKind())) {
            throw DomainException.invalid("ENTITY_KIND_INVALID", "Entity kind must be one of " + KINDS);
        }
        if ("GROUP_MEMBER".equals(in.entityKind())) {
            if (in.groupRepId() == null) {
                throw DomainException.invalid("GROUP_REP_REQUIRED", "A group member needs its group representative");
            }
            LegalEntity rep = find(in.groupRepId());
            if (!"GROUP_REPRESENTATIVE".equals(rep.entityKind())) {
                throw DomainException.invalid("GROUP_REP_INVALID", "The chosen entity is not a tax group representative");
            }
            if (in.trn() != null && !trnValidator.isValid(in.trn())) {
                throw DomainException.invalid("TRN_INVALID", "TRN must be 15 digits");
            }
            return;
        }
        if (in.groupRepId() != null) {
            throw DomainException.invalid("GROUP_REP_NOT_ALLOWED", "Only group members have a group representative");
        }
        if (!trnValidator.isValid(in.trn())) {
            throw DomainException.invalid("TRN_INVALID", "TRN must be 15 digits");
        }
        if (in.filingFrequency() == null || !FREQUENCIES.contains(in.filingFrequency())) {
            throw DomainException.invalid("FREQUENCY_INVALID", "Filing frequency must be MONTHLY or QUARTERLY");
        }
        if (in.firstPeriodStart() == null || in.firstPeriodStart().getDayOfMonth() != 1) {
            throw DomainException.invalid("FIRST_PERIOD_INVALID", "First period must start on the first day of a month");
        }
    }

    /** Settings that can change after onboarding. TRN and calendar are fixed once periods exist. */
    @Transactional
    public LegalEntity updateSettings(UUID id, BigDecimal defaultRecoveryPct, BigDecimal partnerThresholdAed,
                                      Boolean requireReviewer, LocalDate einvoiceGoLive) {
        LegalEntity before = get(id);
        jdbc.sql("""
                UPDATE legal_entity SET
                  default_recovery_pct = COALESCE(:recovery, default_recovery_pct),
                  partner_threshold_aed = :threshold,
                  require_reviewer = COALESCE(:reviewer, require_reviewer),
                  einvoice_go_live = :goLive
                WHERE id = :id
                """)
                .param("recovery", defaultRecoveryPct).param("threshold", partnerThresholdAed)
                .param("reviewer", requireReviewer).param("goLive", einvoiceGoLive).param("id", id)
                .update();
        LegalEntity after = get(id);
        audit.record("ENTITY_SETTINGS_CHANGED", "LEGAL_ENTITY", id, id, before, after, null);
        return after;
    }

    @Transactional(readOnly = true)
    public LegalEntity get(UUID id) {
        return find(id);
    }

    private LegalEntity find(UUID id) {
        return jdbc.sql(LegalEntity.SELECT + " WHERE id = ?").param(id).query(LegalEntity.class).optional()
                .orElseThrow(() -> DomainException.notFound("Entity"));
    }

    /** Entities the current user holds a live grant on. A tenant-wide grant reaches all of them. */
    @Transactional(readOnly = true)
    public List<LegalEntity> listVisible() {
        return jdbc.sql(LegalEntity.SELECT + """
                 WHERE EXISTS (SELECT 1 FROM entity_grant g
                               WHERE g.user_id = :user
                                 AND (g.entity_id IS NULL OR g.entity_id = legal_entity.id)
                                 AND g.valid_from <= now() AND (g.valid_to IS NULL OR g.valid_to > now()))
                 ORDER BY legal_name
                """)
                .param("user", RequestContext.requireUserId())
                .query(LegalEntity.class).list();
    }

    @Transactional(readOnly = true)
    public List<LegalEntity> members(UUID repId) {
        return jdbc.sql(LegalEntity.SELECT + " WHERE id = :id OR group_rep_id = :id ORDER BY legal_name")
                .param("id", repId).query(LegalEntity.class).list();
    }

    @Transactional
    public Establishment addEstablishment(UUID entityId, EstablishmentInput in) {
        get(entityId);
        if (in.code() == null || in.code().isBlank() || in.name() == null || in.name().isBlank()) {
            throw DomainException.invalid("ESTABLISHMENT_INVALID", "Establishment code and name are required");
        }
        if (in.emirate() == null || !Set.of("AUH", "DXB", "SHJ", "AJM", "UAQ", "RAK", "FUJ").contains(in.emirate())) {
            throw DomainException.invalid("EMIRATE_INVALID", "Emirate must be one of AUH, DXB, SHJ, AJM, UAQ, RAK, FUJ");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO establishment (id, tenant_id, entity_id, code, name, emirate, designated_zone, zone_name)
                    VALUES (:id, :tenant, :entity, :code, :name, :emirate, :dz, :zone)
                    """)
                    .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", entityId)
                    .param("code", in.code()).param("name", in.name()).param("emirate", in.emirate())
                    .param("dz", Boolean.TRUE.equals(in.designatedZone())).param("zone", in.zoneName())
                    .update();
        } catch (DuplicateKeyException e) {
            throw DomainException.conflict("ESTABLISHMENT_EXISTS", "Establishment code " + in.code() + " already exists");
        }
        audit.record("ESTABLISHMENT_ADDED", "ESTABLISHMENT", id, entityId, null, in, null);
        return new Establishment(id, entityId, in.code(), in.name(), in.emirate(), Boolean.TRUE.equals(in.designatedZone()), in.zoneName());
    }

    @Transactional(readOnly = true)
    public List<Establishment> establishments(UUID entityId) {
        return jdbc.sql("""
                SELECT id, entity_id, code, name, emirate, designated_zone, zone_name
                FROM establishment WHERE entity_id = ? ORDER BY code
                """).param(entityId).query(Establishment.class).list();
    }

}
