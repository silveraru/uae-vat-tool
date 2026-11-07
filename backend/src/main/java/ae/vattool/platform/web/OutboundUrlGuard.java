package ae.vattool.platform.web;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks a URL a user gave us before the server calls it. Without this, a webhook or connector could
 * be pointed at internal services or the cloud metadata address, and the server would fetch them.
 * Only development turns it off, to reach local stubs.
 */
@Component
public class OutboundUrlGuard {

    private final boolean allowInternal;

    public OutboundUrlGuard(@Value("${app.outbound.allow-internal:false}") boolean allowInternal) {
        this.allowInternal = allowInternal;
    }

    public void check(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw DomainException.invalid("URL_INVALID", "That is not a valid URL");
        }
        if (uri.getHost() == null || uri.getScheme() == null) {
            throw DomainException.invalid("URL_INVALID", "That is not a valid URL");
        }
        if (allowInternal) {
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw DomainException.invalid("URL_NOT_HTTPS", "The URL must use HTTPS");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isInternal(address)) {
                    throw DomainException.invalid("URL_INTERNAL", "The URL points at an internal address");
                }
            }
        } catch (UnknownHostException e) {
            throw DomainException.invalid("URL_UNRESOLVED", "That host name cannot be resolved");
        }
    }

    static boolean isInternal(InetAddress a) {
        return a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()
                || isUniqueLocalV6(a) || isCarrierGradeNat(a);
    }

    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    private static boolean isCarrierGradeNat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }
}
