package ae.vattool.simulator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the VAT tool's AR or AP template and produces one e-invoice per in-scope document, adding
 * up the lines. B2C sales have no e-invoice, as in the real mandate. Simple comma splitting is enough here because the fixtures this reads contain no quoted commas.
 */
final class InvoiceCsv {

    private static final Set<String> IN_SCOPE = Set.of("TAX_INVOICE", "CREDIT_NOTE", "DEBIT_NOTE");

    private InvoiceCsv() {}

    static List<EventFactory.InvoiceSpec> read(Reader source, String direction, String entityTrn) throws IOException {
        try (BufferedReader reader = new BufferedReader(source)) {
            String header = reader.readLine();
            if (header == null) {
                return List.of();
            }
            List<String> cols = Arrays.stream(header.split(",", -1)).map(String::trim).toList();
            Map<String, EventFactory.InvoiceSpec> byDoc = new LinkedHashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                String docType = get(cols, parts, "doc_type");
                String counterparty = get(cols, parts, "counterparty_trn");
                String customerType = get(cols, parts, "customer_type");
                if (!IN_SCOPE.contains(docType) || "B2C".equals(customerType) || !"".equals(get(cols, parts, "void"))) {
                    continue;
                }
                String number = get(cols, parts, "doc_number");
                String date = get(cols, parts, "doc_date");
                int sign = "CREDIT_NOTE".equals(docType) ? -1 : 1;
                BigDecimal net = new BigDecimal(get(cols, parts, "net_amount")).multiply(BigDecimal.valueOf(sign));
                BigDecimal vat = new BigDecimal(get(cols, parts, "vat_amount")).multiply(BigDecimal.valueOf(sign));
                String key = counterparty + "|" + number + "|" + date;
                EventFactory.InvoiceSpec existing = byDoc.get(key);
                if (existing != null) {
                    net = net.add(existing.netAed());
                    vat = vat.add(existing.vatAed());
                }
                String given = get(cols, parts, "einvoice_uuid");
                UUID uuid = given.isEmpty()
                        ? UUID.nameUUIDFromBytes((direction + "|" + entityTrn + "|" + key).getBytes(StandardCharsets.UTF_8))
                        : UUID.fromString(given);
                byDoc.put(key, new EventFactory.InvoiceSpec(direction,
                        "TAX_INVOICE".equals(docType) ? "INVOICE" : docType, number, date,
                        "ISSUED".equals(direction) ? entityTrn : emptyToNull(counterparty),
                        "ISSUED".equals(direction) ? emptyToNull(counterparty) : entityTrn,
                        net, vat, emptyToNull(get(cols, parts, "original_doc_number")), uuid));
            }
            return new ArrayList<>(byDoc.values());
        }
    }

    private static String get(List<String> cols, String[] parts, String col) {
        int i = cols.indexOf(col);
        return i >= 0 && i < parts.length ? parts[i].trim() : "";
    }

    private static String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
