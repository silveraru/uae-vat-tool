package ae.vattool.ingestion;

import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Picks up ERP files that clients drop through SFTP (ING-03). The layout is
 * inbox/&lt;tenantId&gt;/&lt;entityId&gt;/&lt;KIND&gt;/file.csv. Processed files move to processed/, and failed
 * files move to failed/ beside a note explaining why. Files must be uploaded with a temporary name
 * ending in .part and renamed when complete, so half-written files are never read.
 */
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class SftpInboxPoller {
    private static final Logger log = LoggerFactory.getLogger(SftpInboxPoller.class);

    private final IngestionService ingestion;
    private final Path inbox;

    public SftpInboxPoller(IngestionService ingestion, @Value("${app.sftp.inbox}") String inbox) {
        this.ingestion = ingestion;
        this.inbox = Path.of(inbox);
    }

    @Scheduled(fixedDelayString = "${app.sftp.poll-ms:30000}")
    public void poll() {
        if (!Files.isDirectory(inbox)) {
            return;
        }
        try (DirectoryStream<Path> tenants = Files.newDirectoryStream(inbox, Files::isDirectory)) {
            for (Path tenant : tenants) {
                UUID tenantId = parseUuid(tenant.getFileName().toString());
                if (tenantId != null) {
                    pollTenant(tenantId, tenant);
                }
            }
        } catch (IOException e) {
            log.error("Could not read the SFTP inbox {}", inbox, e);
        }
    }

    /** Visible so a test can run one pass without the schedule. */
    public void pollTenant(UUID tenantId, Path tenantDir) throws IOException {
        try (DirectoryStream<Path> entities = Files.newDirectoryStream(tenantDir, Files::isDirectory)) {
            for (Path entity : entities) {
                UUID entityId = parseUuid(entity.getFileName().toString());
                if (entityId == null) {
                    continue;
                }
                try (DirectoryStream<Path> kinds = Files.newDirectoryStream(entity, Files::isDirectory)) {
                    for (Path kindDir : kinds) {
                        pollKind(tenantId, entityId, kindDir);
                    }
                }
            }
        }
    }

    private void pollKind(UUID tenantId, UUID entityId, Path kindDir) throws IOException {
        Templates.Kind kind;
        try {
            kind = Templates.parseKind(kindDir.getFileName().toString());
        } catch (DomainException e) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(kindDir, Files::isRegularFile)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.endsWith(".part") || name.startsWith(".")) {
                    continue;
                }
                try {
                    byte[] content = Files.readAllBytes(file);
                    RequestContext.asSystem(tenantId, "sftp-inbox", () -> ingestion.loadFile(
                            entityId, kind, Templates.CURRENT_VERSION, "SFTP", name, content));
                    move(file, kindDir.resolve("processed"));
                } catch (RuntimeException e) {
                    log.warn("SFTP file {} failed: {}", file, e.getMessage());
                    Path failed = kindDir.resolve("failed");
                    move(file, failed);
                    Files.writeString(failed.resolve(name + ".error.txt"), String.valueOf(e.getMessage()), StandardCharsets.UTF_8);
                }
            }
        }
    }

    private static void move(Path file, Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.move(file, dir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
