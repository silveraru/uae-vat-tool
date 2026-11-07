package ae.vattool.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.vattool.platform.crypto.TenantKeys;
import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** NFR-02 and NFR-03: each tenant's files are encrypted with that tenant's own key. */
class TenantKeysTest extends IntegrationTest {

    @Autowired TenantKeys keys;
    @Autowired VaultStorage vault;

    @Test
    void aFileInStorageIsCiphertextAndComesBackIntact() throws Exception {
        Scenario s = new Scenario(mvc, world);
        UUID entity = s.cleanEntity();

        String id = s.evidence(entity, null, "secret.pdf");
        JsonNode e = s.client.get(s.reviewer, "/api/evidence/" + id);
        String key = e.get("storageKey").asString();

        byte[] raw = world.queryInTenant(s.tenant, () -> vault.getRaw(key));
        byte[] plain = world.queryInTenant(s.tenant, () -> vault.get(key));
        assertThat(TenantKeys.isEncrypted(raw)).isTrue();
        assertThat(new String(raw, StandardCharsets.ISO_8859_1)).doesNotContain("evidence secret.pdf");
        assertThat(new String(plain, StandardCharsets.UTF_8)).startsWith("evidence secret.pdf");
        assertThat(VaultStorage.sha256(plain)).isEqualTo(e.get("sha256").asString());
    }

    @Test
    void oneTenantCannotDecryptAnothersData() {
        UUID a = world.newTenant("A " + UUID.randomUUID());
        UUID b = world.newTenant("B " + UUID.randomUUID());
        byte[] stored = world.queryInTenant(a, () -> keys.encrypt("private".getBytes(StandardCharsets.UTF_8)));
        // Tenant B gets its own key first, so the failure is the wrong key rather than a missing one.
        world.queryInTenant(b, () -> keys.encrypt(new byte[1]));

        assertThatThrownBy(() -> world.queryInTenant(b, () -> keys.decrypt(stored))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void eachTenantsKeyIsDifferentAndStoredOnlyWrapped() throws Exception {
        UUID a = world.newTenant("A " + UUID.randomUUID());
        UUID b = world.newTenant("B " + UUID.randomUUID());
        world.queryInTenant(a, () -> keys.encrypt(new byte[1]));
        world.queryInTenant(b, () -> keys.encrypt(new byte[1]));

        try (Connection c = superuserConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(DISTINCT wrapped_key), MIN(LENGTH(wrapped_key)) FROM tenant_key WHERE tenant_id IN ('" + a + "','" + b + "')")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2);
            // A raw 32-byte key would be 44 characters of base64. Wrapped, it also carries the IV and tag.
            assertThat(rs.getInt(2)).isGreaterThan(44);
        }
    }

    @Test
    void alteredCiphertextIsRefused() {
        UUID a = world.newTenant("A " + UUID.randomUUID());
        byte[] stored = world.queryInTenant(a, () -> keys.encrypt("private".getBytes(StandardCharsets.UTF_8)));
        stored[stored.length - 1] ^= 1;

        assertThatThrownBy(() -> world.queryInTenant(a, () -> keys.decrypt(stored))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anObjectFromBeforeEncryptionStillReads() {
        UUID a = world.newTenant("A " + UUID.randomUUID());
        byte[] old = "written before encryption".getBytes(StandardCharsets.UTF_8);

        assertThat(world.queryInTenant(a, () -> keys.decrypt(old))).isEqualTo(old);
    }

    @Test
    void afterRotationOldObjectsStillOpenAndNewOnesUseTheNewKey() {
        UUID a = world.newTenant("A " + UUID.randomUUID());
        byte[] before = world.queryInTenant(a, () -> keys.encrypt("old".getBytes(StandardCharsets.UTF_8)));

        int version = world.queryInTenant(a, () -> keys.rotate());
        byte[] after = world.queryInTenant(a, () -> keys.encrypt("new".getBytes(StandardCharsets.UTF_8)));

        assertThat(version).isEqualTo(2);
        assertThat(new String(world.queryInTenant(a, () -> keys.decrypt(before)), StandardCharsets.UTF_8)).isEqualTo("old");
        assertThat(new String(world.queryInTenant(a, () -> keys.decrypt(after)), StandardCharsets.UTF_8)).isEqualTo("new");
    }
}
