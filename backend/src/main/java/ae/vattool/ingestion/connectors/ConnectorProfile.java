package ae.vattool.ingestion.connectors;

import ae.vattool.platform.web.DomainException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * How one ERP's data maps onto the upload templates, as data (ING-04). A profile is REST when the
 * system has an API this can call, or FILE when the practical route is the system's own export. No
 * bundled profile has been run against a live system, which is what status says, and a firm can
 * correct a field name here without a code release.
 */
public record ConnectorProfile(String system, String label, String status, String transport, String note, Map<String, KindMap> kinds) {

    public record KindMap(String path, Map<String, String> params, String itemsPath, Paging paging, Map<String, String> constants, Map<String, String> fields) {}

    public record Paging(String param, int start, String sizeParam, int size, String style) {}

    private static final YAMLMapper YAML = YAMLMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public static Map<String, ConnectorProfile> loadAll() {
        Map<String, ConnectorProfile> out = new TreeMap<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath:connectors/*.yaml")) {
                try (InputStream in = r.getInputStream()) {
                    ConnectorProfile p = YAML.readValue(in, ConnectorProfile.class);
                    out.put(p.system(), p);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Connector profiles could not be read", e);
        }
        return out;
    }

    public KindMap kind(String kind) {
        KindMap k = kinds == null ? null : kinds.get(kind);
        if (k == null) {
            throw DomainException.invalid("CONNECTOR_KIND_UNSUPPORTED", label + " has no mapping for " + kind);
        }
        return k;
    }

    public boolean isRest() {
        return "REST".equals(transport);
    }

    public List<String> kindNames() {
        return kinds == null ? List.of() : List.copyOf(kinds.keySet());
    }
}
