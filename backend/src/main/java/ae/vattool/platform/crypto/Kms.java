package ae.vattool.platform.crypto;

/**
 * The key service that wraps each tenant's data key (NFR-02, NFR-03). Data keys never leave the
 * process unwrapped. The tenant is bound to each wrap, so one tenant's stored key cannot be unwrapped
 * as another's. Choosing the provider is a hosting decision, so this is the seam.
 */
public interface Kms {

    /** A reference recorded beside the wrapped key, naming which master key made it. */
    String keyRef();

    byte[] wrap(byte[] dataKey, String tenantId);

    byte[] unwrap(byte[] wrapped, String tenantId);
}
