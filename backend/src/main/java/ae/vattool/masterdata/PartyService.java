package ae.vattool.masterdata;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tax agents, signatories (ONB-05), the counterparty register (ONB-07, R08) and staff access. */
@Service
public class PartyService {

    public record AgentLink(String agencyName, String tan, String agentName, String taan, LocalDate validFrom) {}

    public record Signatory(UUID id, String name, String email, String position, LocalDate validFrom, LocalDate validTo) {}

    public record CounterpartyInput(String trn, String name, String country, boolean designatedZone, String trnStatus,
                                    LocalDate activeFrom, LocalDate activeTo, LocalDate checkedOn) {}

    public record Counterparty(UUID id, String trn, String name, String country, boolean designatedZone, String trnStatus,
                               LocalDate activeFrom, LocalDate activeTo, LocalDate checkedOn) {}

    public record UserInput(String subject, String email, String displayName, String side) {}

    public record User(UUID id, String subject, String email, String displayName, String side, boolean active) {}

    public record GrantInput(UUID userId, String role, java.time.OffsetDateTime validTo) {}

    public record Grant(UUID id, UUID userId, UUID entityId, String role, java.time.OffsetDateTime validFrom, java.time.OffsetDateTime validTo) {}

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final TrnValidator trnValidator;

    public PartyService(JdbcClient jdbc, AuditLog audit, TrnValidator trnValidator) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.trnValidator = trnValidator;
    }

    @Transactional
    public AgentLink linkAgent(UUID entityId, AgentLink in) {
        UUID tenant = RequestContext.requireTenant();
        if (in.tan() == null || in.taan() == null || in.agentName() == null || in.agencyName() == null) {
            throw DomainException.invalid("AGENT_INVALID", "Agency name, TAN, agent name and TAAN are required");
        }
        jdbc.sql("""
                INSERT INTO tax_agency (id, tenant_id, name, tan) VALUES (:id, :tenant, :name, :tan)
                ON CONFLICT (tenant_id, tan) DO UPDATE SET name = EXCLUDED.name
                """)
                .param("id", UUID.randomUUID()).param("tenant", tenant).param("name", in.agencyName()).param("tan", in.tan())
                .update();
        UUID agencyId = jdbc.sql("SELECT id FROM tax_agency WHERE tan = ?").param(in.tan()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO tax_agent (id, tenant_id, agency_id, name, taan) VALUES (:id, :tenant, :agency, :name, :taan)
                ON CONFLICT (tenant_id, taan) DO UPDATE SET name = EXCLUDED.name, agency_id = EXCLUDED.agency_id
                """)
                .param("id", UUID.randomUUID()).param("tenant", tenant).param("agency", agencyId)
                .param("name", in.agentName()).param("taan", in.taan())
                .update();
        UUID agentId = jdbc.sql("SELECT id FROM tax_agent WHERE taan = ?").param(in.taan()).query(UUID.class).single();
        LocalDate from = in.validFrom() == null ? LocalDate.now() : in.validFrom();
        // Appointing a new agent ends the previous appointment, keeping the history.
        jdbc.sql("UPDATE entity_tax_agent SET valid_to = ? WHERE entity_id = ? AND valid_to IS NULL")
                .params(from, entityId).update();
        jdbc.sql("""
                INSERT INTO entity_tax_agent (id, tenant_id, entity_id, agent_id, valid_from)
                VALUES (:id, :tenant, :entity, :agent, :from)
                """)
                .param("id", UUID.randomUUID()).param("tenant", tenant).param("entity", entityId)
                .param("agent", agentId).param("from", from)
                .update();
        audit.record("TAX_AGENT_LINKED", "LEGAL_ENTITY", entityId, entityId, null, in, null);
        return new AgentLink(in.agencyName(), in.tan(), in.agentName(), in.taan(), from);
    }

    @Transactional(readOnly = true)
    public List<AgentLink> agents(UUID entityId) {
        return jdbc.sql("""
                SELECT ag.name AS agency_name, ag.tan, a.name AS agent_name, a.taan, l.valid_from
                FROM entity_tax_agent l
                JOIN tax_agent a ON a.id = l.agent_id
                JOIN tax_agency ag ON ag.id = a.agency_id
                WHERE l.entity_id = ? AND l.valid_to IS NULL
                """).param(entityId).query(AgentLink.class).list();
    }

    @Transactional
    public Signatory addSignatory(UUID entityId, Signatory in) {
        if (in.name() == null || in.name().isBlank() || in.email() == null || in.email().isBlank()) {
            throw DomainException.invalid("SIGNATORY_INVALID", "Signatory name and email are required");
        }
        UUID id = UUID.randomUUID();
        LocalDate from = in.validFrom() == null ? LocalDate.now() : in.validFrom();
        jdbc.sql("""
                INSERT INTO authorised_signatory (id, tenant_id, entity_id, name, email, position, valid_from, valid_to)
                VALUES (:id, :tenant, :entity, :name, :email, :position, :from, :to)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", entityId)
                .param("name", in.name()).param("email", in.email()).param("position", in.position())
                .param("from", from).param("to", in.validTo())
                .update();
        audit.record("SIGNATORY_ADDED", "AUTHORISED_SIGNATORY", id, entityId, null, in, null);
        return new Signatory(id, in.name(), in.email(), in.position(), from, in.validTo());
    }

    @Transactional(readOnly = true)
    public List<Signatory> signatories(UUID entityId) {
        return jdbc.sql("""
                SELECT id, name, email, position, valid_from, valid_to FROM authorised_signatory
                WHERE entity_id = ? ORDER BY name
                """).param(entityId).query(Signatory.class).list();
    }

    @Transactional
    public Counterparty upsertCounterparty(CounterpartyInput in) {
        if (!trnValidator.isValid(in.trn())) {
            throw DomainException.invalid("TRN_INVALID", "TRN must be 15 digits");
        }
        if (in.name() == null || in.name().isBlank()) {
            throw DomainException.invalid("NAME_REQUIRED", "Counterparty name is required");
        }
        jdbc.sql("""
                INSERT INTO counterparty (id, tenant_id, trn, name, country, designated_zone, trn_status, active_from, active_to, checked_on)
                VALUES (:id, :tenant, :trn, :name, :country, :dz, :status, :from, :to, :checked)
                ON CONFLICT (tenant_id, trn) DO UPDATE SET name = EXCLUDED.name, country = EXCLUDED.country,
                    designated_zone = EXCLUDED.designated_zone, trn_status = EXCLUDED.trn_status,
                    active_from = EXCLUDED.active_from, active_to = EXCLUDED.active_to, checked_on = EXCLUDED.checked_on
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant())
                .param("trn", in.trn()).param("name", in.name())
                .param("country", in.country() == null ? "AE" : in.country()).param("dz", in.designatedZone())
                .param("status", in.trnStatus() == null ? "UNKNOWN" : in.trnStatus())
                .param("from", in.activeFrom()).param("to", in.activeTo()).param("checked", in.checkedOn())
                .update();
        Counterparty saved = jdbc.sql(COUNTERPARTY_SELECT + " WHERE trn = ?").param(in.trn()).query(Counterparty.class).single();
        audit.record("COUNTERPARTY_SAVED", "COUNTERPARTY", saved.id(), null, null, in, null);
        return saved;
    }

    private static final String COUNTERPARTY_SELECT = """
            SELECT id, trn, name, country, designated_zone, trn_status, active_from, active_to, checked_on FROM counterparty
            """;

    @Transactional(readOnly = true)
    public List<Counterparty> counterparties() {
        return jdbc.sql(COUNTERPARTY_SELECT + " ORDER BY name LIMIT 1000").query(Counterparty.class).list();
    }

    @Transactional
    public User createUser(UserInput in) {
        if (in.subject() == null || in.subject().isBlank() || in.email() == null || in.displayName() == null) {
            throw DomainException.invalid("USER_INVALID", "Subject, email and display name are required");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO app_user (id, tenant_id, subject, email, display_name, side)
                    VALUES (:id, :tenant, :subject, :email, :name, :side)
                    """)
                    .param("id", id).param("tenant", RequestContext.requireTenant()).param("subject", in.subject())
                    .param("email", in.email()).param("name", in.displayName()).param("side", in.side())
                    .update();
        } catch (DuplicateKeyException e) {
            throw DomainException.conflict("USER_EXISTS", "A user with this subject already exists");
        }
        jdbc.sql("SELECT register_login(?, ?)").params(in.subject(), id).query().listOfRows();
        audit.record("USER_CREATED", "APP_USER", id, null, null, Map.of("email", in.email(), "side", in.side()), null);
        return new User(id, in.subject(), in.email(), in.displayName(), in.side(), true);
    }

    @Transactional(readOnly = true)
    public List<User> users() {
        return jdbc.sql("SELECT id, subject, email, display_name, side, active FROM app_user ORDER BY display_name")
                .query(User.class).list();
    }

    @Transactional
    public Grant grant(UUID entityId, GrantInput in) {
        // Spec section 2: external auditors get read-only access that must expire.
        if ("AUDITOR".equals(in.role()) && in.validTo() == null) {
            throw DomainException.invalid("AUDITOR_ACCESS_MUST_EXPIRE", "External auditor access needs an end date");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO entity_grant (id, tenant_id, user_id, entity_id, role, valid_to, granted_by)
                VALUES (:id, :tenant, :user, :entity, :role, :to, :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("user", in.userId())
                .param("entity", entityId).param("role", in.role()).param("to", in.validTo())
                .param("by", RequestContext.requireActor().actorId())
                .update();
        audit.record("ACCESS_GRANTED", "ENTITY_GRANT", id, entityId, null,
                Map.of("userId", in.userId().toString(), "role", in.role()), null);
        return jdbc.sql("SELECT id, user_id, entity_id, role, valid_from, valid_to FROM entity_grant WHERE id = ?")
                .param(id).query(Grant.class).single();
    }

    @Transactional
    public void revokeGrant(UUID entityId, UUID grantId) {
        int changed = jdbc.sql("UPDATE entity_grant SET valid_to = now() WHERE id = ? AND entity_id = ?")
                .params(grantId, entityId).update();
        if (changed == 0) {
            throw DomainException.notFound("Access grant");
        }
        audit.record("ACCESS_REVOKED", "ENTITY_GRANT", grantId, entityId, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<Grant> grants(UUID entityId) {
        return jdbc.sql("""
                SELECT id, user_id, entity_id, role, valid_from, valid_to FROM entity_grant
                WHERE (entity_id = ? OR entity_id IS NULL) AND valid_from <= now() AND (valid_to IS NULL OR valid_to > now())
                """).param(entityId).query(Grant.class).list();
    }
}
