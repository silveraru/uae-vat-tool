package ae.vattool.platform.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHold;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * WORM storage for raw files, evidence and filed snapshots (AUD-02, NFR-06). Every object is written
 * with an Object Lock retention date and cannot be overwritten or deleted before it. Keys are always
 * prefixed with the tenant by the callers below, so storage keeps the same separation as the database.
 */
@Service
public class VaultStorage {

    public record Stored(String key, String versionId, String sha256, long size, LocalDate retainUntil) {}

    public record Retention(String mode, Instant retainUntil, boolean legalHold) {}

    private final S3Client s3;
    private final String bucket;
    private final ObjectLockMode lockMode;
    private volatile boolean bucketReady;

    public VaultStorage(@Value("${app.storage.endpoint}") String endpoint,
                        @Value("${app.storage.region}") String region,
                        @Value("${app.storage.access-key}") String accessKey,
                        @Value("${app.storage.secret-key}") String secretKey,
                        @Value("${app.storage.bucket}") String bucket,
                        @Value("${app.storage.lock-mode}") String lockMode) {
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        this.bucket = bucket;
        this.lockMode = ObjectLockMode.fromValue(lockMode);
    }

    public Stored put(String key, byte[] content, String contentType, LocalDate retainUntil) {
        ensureBucket();
        var response = s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .objectLockMode(lockMode)
                        .objectLockRetainUntilDate(retainUntil.atStartOfDay().toInstant(ZoneOffset.UTC))
                        .build(),
                RequestBody.fromBytes(content));
        return new Stored(key, response.versionId(), sha256(content), content.length, retainUntil);
    }

    public byte[] get(String key) {
        ensureBucket();
        return s3.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray();
    }

    /** Tries to delete a specific version. Fails while the retention date or a legal hold applies. */
    public void deleteVersion(String key, String versionId) {
        ensureBucket();
        s3.deleteObject(r -> r.bucket(bucket).key(key).versionId(versionId));
    }

    public void setLegalHold(String key, boolean on) {
        ensureBucket();
        s3.putObjectLegalHold(r -> r.bucket(bucket).key(key).legalHold(ObjectLockLegalHold.builder()
                .status(on ? ObjectLockLegalHoldStatus.ON : ObjectLockLegalHoldStatus.OFF)
                .build()));
    }

    public Retention retention(String key) {
        ensureBucket();
        var retention = s3.getObjectRetention(r -> r.bucket(bucket).key(key)).retention();
        boolean hold;
        try {
            hold = s3.getObjectLegalHold(r -> r.bucket(bucket).key(key)).legalHold().status() == ObjectLockLegalHoldStatus.ON;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            hold = false; // no legal hold has ever been set on this object
        }
        ObjectLockRetentionMode mode = retention.mode();
        return new Retention(mode == null ? null : mode.toString(), retention.retainUntilDate(), hold);
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            try {
                s3.headBucket(r -> r.bucket(bucket));
            } catch (NoSuchBucketException e) {
                // Object Lock can only be switched on when a bucket is created.
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).objectLockEnabledForBucket(true).build());
            }
            bucketReady = true;
        }
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
