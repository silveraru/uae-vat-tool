package ae.vattool.ingestion.connectors;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Turns a record from an ERP into a template row. Amounts and dates are put in the template's form:
 * OData and REST APIs send four decimals and timestamps, and the template wants two and a plain date.
 * Any real extra precision stays, so the normal validation rejects it rather than rounding it here.
 */
final class RowMapper {

    private static final Pattern ODATA_DATE = Pattern.compile("/Date\\((-?\\d+)(?:[+-]\\d+)?\\)/");
    private static final ZoneId UAE = ZoneId.of("Asia/Dubai");

    private RowMapper() {}

    static Map<String, String> fromJson(ConnectorProfile.KindMap map, JsonNode item) {
        Map<String, String> row = new LinkedHashMap<>();
        if (map.constants() != null) {
            row.putAll(map.constants());
        }
        map.fields().forEach((target, source) -> row.put(target, normalise(target, text(item, source))));
        return row;
    }

    static Map<String, String> fromColumns(ConnectorProfile.KindMap map, Map<String, String> source) {
        Map<String, String> row = new LinkedHashMap<>();
        if (map.constants() != null) {
            row.putAll(map.constants());
        }
        map.fields().forEach((target, column) -> row.put(target, normalise(target, source.get(column))));
        return row;
    }

    /** Follows a dotted path through nested objects. */
    static JsonNode at(JsonNode node, String path) {
        JsonNode cursor = node;
        for (String part : path.split("\\.")) {
            if (cursor == null || cursor.isMissingNode()) {
                return null;
            }
            cursor = cursor.get(part);
        }
        return cursor;
    }

    private static String text(JsonNode item, String path) {
        JsonNode value = at(item, path);
        return value == null || value.isNull() ? null : value.asString();
    }

    static String normalise(String target, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String v = value.trim();
        if (target.endsWith("_date") || target.equals("transaction_date")) {
            Matcher m = ODATA_DATE.matcher(v);
            if (m.matches()) {
                return Instant.ofEpochMilli(Long.parseLong(m.group(1))).atZone(UAE).toLocalDate().toString();
            }
            return v.length() > 10 && v.charAt(4) == '-' ? v.substring(0, 10) : v;
        }
        if (target.endsWith("_amount") || target.endsWith("_aed") || target.equals("debit") || target.equals("credit")) {
            try {
                BigDecimal d = new BigDecimal(v.replace(",", ""));
                BigDecimal stripped = d.stripTrailingZeros();
                return stripped.scale() <= 2 ? d.setScale(2, RoundingMode.UNNECESSARY).toPlainString() : d.toPlainString();
            } catch (NumberFormatException e) {
                return v;
            }
        }
        return v;
    }
}
