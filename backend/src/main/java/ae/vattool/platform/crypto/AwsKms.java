package ae.vattool.platform.crypto;

import java.net.URI;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;

/**
 * A key service using the AWS KMS API, which a UAE-region deployment can point at. The tenant goes in
 * the encryption context, so KMS itself refuses to unwrap one tenant's key as another's.
 */
final class AwsKms implements Kms {

    private final KmsClient kms;
    private final String keyId;

    AwsKms(String keyId, String region, String endpoint) {
        KmsClientBuilder builder = KmsClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        this.kms = builder.build();
        this.keyId = keyId;
    }

    @Override
    public String keyRef() {
        return keyId;
    }

    @Override
    public byte[] wrap(byte[] dataKey, String tenantId) {
        return kms.encrypt(r -> r.keyId(keyId).plaintext(SdkBytes.fromByteArray(dataKey)).encryptionContext(Map.of("tenant", tenantId)))
                .ciphertextBlob().asByteArray();
    }

    @Override
    public byte[] unwrap(byte[] wrapped, String tenantId) {
        return kms.decrypt(r -> r.keyId(keyId).ciphertextBlob(SdkBytes.fromByteArray(wrapped)).encryptionContext(Map.of("tenant", tenantId)))
                .plaintext().asByteArray();
    }
}
