package ae.vattool.reconciliation;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Peppol SMP participant lookup for check R08. Stubbed: real lookups need the ASP's Peppol access.
 * The default answers "unknown", and R08 reports that it did not check, not that the TRN is fine.
 */
public interface ParticipantLookup {

    Optional<Boolean> isRegistered(String trn);

    @Component
    class NotConnected implements ParticipantLookup {
        @Override
        public Optional<Boolean> isRegistered(String trn) {
            return Optional.empty();
        }
    }
}
