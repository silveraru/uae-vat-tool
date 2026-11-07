package ae.vattool.reconciliation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Looks a counterparty up in the Peppol network by its DNS record, which is how the network's Service
 * Metadata Locator publishes participants. In the UAE the identifier is scheme 0235 and the 10-digit
 * TIN, which is the first ten digits of a 15-digit TRN. A DNS failure means "did not check", never
 * "not registered", and a miss is not an error in itself: a business may be on a Tax Group TRN, whose
 * first ten digits are not any member's TIN. That is why R08 stays a warning.
 */
@Component
@ConditionalOnProperty(name = "app.peppol.lookup.enabled", havingValue = "true")
public class PeppolDnsLookup implements ParticipantLookup {
    private static final Logger log = LoggerFactory.getLogger(PeppolDnsLookup.class);
    private static final Duration REMEMBER_FOR = Duration.ofHours(24);
    private static final int MAX_REMEMBERED = 100_000;

    /** Answers whether a DNS name exists. Separate so a test can stand in for the network. */
    interface Resolver {
        boolean exists(String name) throws NamingException;
    }

    private record Remembered(boolean registered, Instant until) {}

    private final Resolver resolver;
    private final String domain;
    private final Map<String, Remembered> cache = new ConcurrentHashMap<>();

    @Autowired
    public PeppolDnsLookup(@Value("${app.peppol.lookup.sml-domain}") String smlDomain) {
        this(PeppolDnsLookup::naptrExists, smlDomain);
    }

    PeppolDnsLookup(Resolver resolver, String smlDomain) {
        this.resolver = resolver;
        this.domain = smlDomain;
    }

    @Override
    public Optional<Boolean> isRegistered(String trn) {
        if (trn == null || trn.length() < 10) {
            return Optional.empty();
        }
        String tin = trn.substring(0, 10);
        Remembered known = cache.get(tin);
        if (known != null && known.until().isAfter(Instant.now())) {
            return Optional.of(known.registered());
        }
        try {
            boolean registered = resolver.exists(dnsName(tin, domain));
            if (cache.size() >= MAX_REMEMBERED) {
                cache.clear();
            }
            cache.put(tin, new Remembered(registered, Instant.now().plus(REMEMBER_FOR)));
            return Optional.of(registered);
        } catch (NamingException e) {
            log.debug("Peppol lookup failed for a counterparty: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** The Base32 of the SHA-256 of the lower-case participant id, under the scheme and locator domain. */
    static String dnsName(String tin, String smlDomain) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(("0235:" + tin).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return base32(hash) + ".iso6523-actorid-upis." + smlDomain;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base32(byte[] bytes) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(alphabet.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(alphabet.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    private static boolean naptrExists(String name) throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "1500");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        InitialDirContext dns = new InitialDirContext(env);
        try {
            Attributes found = dns.getAttributes(name, new String[] {"NAPTR"});
            return found.size() > 0;
        } catch (NameNotFoundException notFound) {
            return false;
        } finally {
            dns.close();
        }
    }
}
