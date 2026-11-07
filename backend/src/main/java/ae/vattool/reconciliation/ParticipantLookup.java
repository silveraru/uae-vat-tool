package ae.vattool.reconciliation;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Peppol participant lookup for check R08. Empty means "did not check", which R08 reports as such,
 * so it can never read as the TRN being fine. See PeppolDnsLookup for the live version.
 */
public interface ParticipantLookup {

    Optional<Boolean> isRegistered(String trn);

    @Component
    @ConditionalOnProperty(name = "app.peppol.lookup.enabled", havingValue = "false", matchIfMissing = true)
    class NotConnected implements ParticipantLookup {
        @Override
        public Optional<Boolean> isRegistered(String trn) {
            return Optional.empty();
        }
    }
}
