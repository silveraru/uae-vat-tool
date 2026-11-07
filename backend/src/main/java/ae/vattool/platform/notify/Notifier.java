package ae.vattool.platform.notify;

import ae.vattool.platform.documents.DocText;
import ae.vattool.platform.tenancy.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends an in-app notice and an email to everyone holding a role on an entity, each in their own
 * language. A mail failure never loses the in-app notice.
 */
@Service
public class Notifier {
    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    /** An argument that is itself a piece of wording, so it follows the reader's language too. */
    public record Text(String key) {}

    private record Recipient(UUID id, String email, String displayName, String locale) {}

    private final JdbcClient jdbc;
    private final JavaMailSender mail;
    private final MeterRegistry metrics;

    public Notifier(JdbcClient jdbc, JavaMailSender mail, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.metrics = metrics;
    }

    /** titleKey and bodyKey name entries in the document wording. Returns how many people were told. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int toRoles(UUID entityId, UUID periodId, String kind, List<String> roles, String titleKey, String bodyKey, Object... args) {
        List<Recipient> people = jdbc.sql("""
                SELECT DISTINCT u.id, u.email, u.display_name, u.locale FROM app_user u
                JOIN entity_grant g ON g.user_id = u.id
                WHERE u.active AND (g.entity_id = :entity OR g.entity_id IS NULL) AND g.role IN (:roles)
                  AND g.valid_from <= now() AND (g.valid_to IS NULL OR g.valid_to > now())
                """).param("entity", entityId).param("roles", roles).query(Recipient.class).list();
        send(people, entityId, periodId, kind, titleKey, bodyKey, args);
        return people.size();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int toTenantRoles(String kind, List<String> roles, String titleKey, String bodyKey, Object... args) {
        List<Recipient> people = jdbc.sql("""
                SELECT DISTINCT u.id, u.email, u.display_name, u.locale FROM app_user u
                JOIN entity_grant g ON g.user_id = u.id
                WHERE u.active AND g.entity_id IS NULL AND g.role IN (:roles)
                  AND g.valid_from <= now() AND (g.valid_to IS NULL OR g.valid_to > now())
                """).param("roles", roles).query(Recipient.class).list();
        send(people, null, null, kind, titleKey, bodyKey, args);
        return people.size();
    }

    private void send(List<Recipient> people, UUID entityId, UUID periodId, String kind, String titleKey, String bodyKey, Object[] args) {
        for (Recipient r : people) {
            DocText text = DocText.of(r.locale());
            Object[] resolved = args.clone();
            for (int i = 0; i < resolved.length; i++) {
                if (resolved[i] instanceof Text word) {
                    resolved[i] = text.t(word.key());
                }
            }
            String title = text.t(titleKey, resolved);
            String body = text.t(bodyKey, resolved);
            jdbc.sql("""
                    INSERT INTO notification (id, tenant_id, user_id, entity_id, period_id, kind, title, body)
                    VALUES (:id, :tenant, :user, :entity, :period, :kind, :title, :body)
                    """)
                    .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("user", r.id())
                    .param("entity", entityId).param("period", periodId).param("kind", kind)
                    .param("title", title).param("body", body)
                    .update();
            mail(r, title, body);
        }
    }

    private void mail(Recipient r, String title, String body) {
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setTo(r.email());
            m.setFrom("no-reply@vat-tool.local");
            m.setSubject(title);
            m.setText(body);
            mail.send(m);
        } catch (RuntimeException e) {
            metrics.counter("vat.notify.mail_failed").increment();
            log.warn("Could not send an email to {}: {}", r.id(), e.getMessage());
        }
    }
}
