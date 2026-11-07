package ae.vattool.platform.documents;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Fixed wording for generated documents and notifications, in English and Arabic. */
public final class DocText {

    private static final Map<String, Map<String, String>> TEXT = load();
    private static final DocText EN = new DocText("en");
    private static final DocText AR = new DocText("ar");

    private final String lang;

    private DocText(String lang) {
        this.lang = lang;
    }

    public static DocText of(String lang) {
        return "ar".equals(lang) ? AR : EN;
    }

    public String lang() {
        return lang;
    }

    /** A missing key is a build mistake, not a reason to send a blank line to a tax authority. */
    public String t(String key, Object... args) {
        Map<String, String> entry = TEXT.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("No document text for " + key);
        }
        String text = entry.getOrDefault(lang, entry.get("en"));
        for (int i = 0; i + 1 < args.length; i += 2) {
            text = text.replace("{" + args[i] + "}", String.valueOf(args[i + 1]));
        }
        return text;
    }

    public boolean has(String key) {
        return TEXT.containsKey(key);
    }

    static java.util.Set<String> keys() {
        return TEXT.keySet();
    }

    static String raw(String key, String lang) {
        return TEXT.get(key).get(lang);
    }

    private static Map<String, Map<String, String>> load() {
        try (InputStream in = new ClassPathResource("doc-text.json").getInputStream()) {
            return JsonMapper.builder().build().readValue(in, new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("doc-text.json is missing from the build", e);
        }
    }
}
