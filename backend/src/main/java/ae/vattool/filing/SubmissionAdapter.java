package ae.vattool.filing;

import ae.vattool.returns.ReturnVersionView;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The slot for direct VAT201 submission (FIL-04). As far as is known the FTA publishes no such API, so
 * a person submits on EmaraTax and records the reference. When an API exists, an adapter goes here
 * and nothing else changes. Automating EmaraTax's web pages is ruled out by the spec, so no adapter
 * may do that: it is brittle and would put the ASP accreditation at risk.
 */
public interface SubmissionAdapter {

    record Receipt(String submissionRef) {}

    boolean available();

    /** Why it is not available, for the person reading the screen. */
    Optional<String> unavailableReason();

    Receipt submit(ReturnVersionView version);

    @Component
    class NoFtaApi implements SubmissionAdapter {

        private final boolean enabled;

        public NoFtaApi(@Value("${app.fta.direct-submission-enabled:false}") boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Optional<String> unavailableReason() {
            return Optional.of(enabled
                    ? "Direct submission is switched on, but no adapter is installed."
                    : "The FTA has not published a submission API. Submit on EmaraTax and record the reference.");
        }

        @Override
        public Receipt submit(ReturnVersionView version) {
            throw new IllegalStateException("There is no FTA submission API to call");
        }
    }
}
