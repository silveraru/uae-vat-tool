package ae.vattool.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.naming.NamingException;
import org.junit.jupiter.api.Test;

class PeppolDnsLookupTest {

    @Test
    void theDnsNameIsTheBase32OfTheHashedParticipantId() {
        // Worked out separately in Python from the lower-case id 0235:1234567890.
        assertThat(PeppolDnsLookup.dnsName("1234567890", "edelivery.tech.ec.europa.eu"))
                .isEqualTo("FQKZXDDXQ72L2T6OC4HIMREZYXUT6B2QC3YWZJPJAKKHAX3AELIA.iso6523-actorid-upis.edelivery.tech.ec.europa.eu");
    }

    @Test
    void theParticipantIsTheFirstTenDigitsOfTheTrn() {
        List<String> asked = new ArrayList<>();
        PeppolDnsLookup lookup = new PeppolDnsLookup(name -> {
            asked.add(name);
            return true;
        }, "sml.example");

        assertThat(lookup.isRegistered("123456789000003")).contains(true);
        assertThat(asked.getFirst()).isEqualTo(PeppolDnsLookup.dnsName("1234567890", "sml.example"));
    }

    @Test
    void aMissingNameIsNotRegistered() {
        assertThat(new PeppolDnsLookup(name -> false, "sml.example").isRegistered("123456789000003")).contains(false);
    }

    @Test
    void aDnsFailureMeansNotCheckedRatherThanNotRegistered() {
        PeppolDnsLookup lookup = new PeppolDnsLookup(name -> {
            throw new NamingException("timeout");
        }, "sml.example");

        assertThat(lookup.isRegistered("123456789000003")).isEqualTo(Optional.empty());
    }

    @Test
    void anAnswerIsRememberedSoAReconRunDoesNotAskTwice() {
        int[] calls = {0};
        PeppolDnsLookup lookup = new PeppolDnsLookup(name -> {
            calls[0]++;
            return true;
        }, "sml.example");

        lookup.isRegistered("123456789000003");
        lookup.isRegistered("123456789000003");

        assertThat(calls[0]).isEqualTo(1);
    }
}
