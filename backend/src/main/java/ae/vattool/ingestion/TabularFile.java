package ae.vattool.ingestion;

import ae.vattool.platform.web.DomainException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Reads a CSV or XLSX upload into rows keyed by lower-case column name. */
public final class TabularFile {

    public record Parsed(Set<String> headers, List<Map<String, String>> rows) {}

    private TabularFile() {}

    public static Parsed read(String fileName, byte[] content) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".xlsx")) {
                return readXlsx(content);
            }
            if (name.endsWith(".csv") || name.endsWith(".txt")) {
                return readCsv(content);
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof DomainException d) {
                throw d;
            }
            throw DomainException.invalid("FILE_UNREADABLE", "The file could not be read: " + e.getMessage());
        }
        throw DomainException.invalid("FILE_TYPE_UNSUPPORTED", "Upload a .csv or .xlsx file");
    }

    private static Parsed readCsv(byte[] content) throws IOException {
        int offset = hasUtf8Bom(content) ? 3 : 0;
        try (Reader reader = new InputStreamReader(
                new ByteArrayInputStream(content, offset, content.length - offset), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true).setTrim(true).get().parse(reader)) {
            Set<String> headers = new LinkedHashSet<>();
            parser.getHeaderNames().forEach(h -> headers.add(normalise(h)));
            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String h : parser.getHeaderNames()) {
                    String value = record.isSet(h) ? record.get(h) : "";
                    row.put(normalise(h), value == null ? "" : value.trim());
                }
                rows.add(row);
            }
            return new Parsed(headers, rows);
        }
    }

    private static boolean hasUtf8Bom(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF;
    }

    private static Parsed readXlsx(byte[] content) throws IOException {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw DomainException.invalid("FILE_EMPTY", "The workbook has no header row");
            }
            List<String> columns = new ArrayList<>();
            for (Cell c : headerRow) {
                while (columns.size() < c.getColumnIndex()) {
                    columns.add("");
                }
                columns.add(normalise(c.getStringCellValue()));
            }
            Set<String> headers = new LinkedHashSet<>(columns);
            headers.remove("");
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                boolean any = false;
                for (int c = 0; c < columns.size(); c++) {
                    if (columns.get(c).isEmpty()) {
                        continue;
                    }
                    String value = text(r.getCell(c));
                    row.put(columns.get(c), value);
                    any |= !value.isEmpty();
                }
                if (any) {
                    rows.add(row);
                }
            }
            return new Parsed(headers, rows);
        }
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            default -> "";
        };
    }

    private static String normalise(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
