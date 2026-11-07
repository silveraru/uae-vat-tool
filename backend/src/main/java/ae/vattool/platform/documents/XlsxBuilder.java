package ae.vattool.platform.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** A small workbook writer: every sheet opens with the watermark when the rules are unsigned. */
public final class XlsxBuilder {

    private final XSSFWorkbook wb = new XSSFWorkbook();
    private final XSSFCellStyle bold;
    private final XSSFCellStyle warning;
    private final XSSFCellStyle number;
    private final boolean rtl;
    private final String watermark;
    private Sheet sheet;
    private int rowNo;

    public XlsxBuilder(String lang, boolean draft) {
        rtl = "ar".equals(lang);
        watermark = draft ? PdfRenderer.watermark(lang) : null;
        bold = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        bold.setFont(font);
        warning = wb.createCellStyle();
        Font red = wb.createFont();
        red.setBold(true);
        red.setColor(IndexedColors.RED.getIndex());
        warning.setFont(red);
        number = wb.createCellStyle();
        number.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
    }

    public XlsxBuilder sheet(String name) {
        sheet = wb.createSheet(name);
        sheet.setRightToLeft(rtl);
        rowNo = 0;
        if (watermark != null) {
            Row w = sheet.createRow(rowNo++);
            w.createCell(0).setCellValue(watermark);
            w.getCell(0).setCellStyle(warning);
        }
        return this;
    }

    public XlsxBuilder header(Object... cells) {
        write(cells, true);
        return this;
    }

    public XlsxBuilder row(Object... cells) {
        write(cells, false);
        return this;
    }

    public XlsxBuilder blank() {
        rowNo++;
        return this;
    }

    public XlsxBuilder widths(int... chars) {
        for (int i = 0; i < chars.length; i++) {
            sheet.setColumnWidth(i, chars[i] * 256);
        }
        return this;
    }

    private void write(Object[] cells, boolean header) {
        Row r = sheet.createRow(rowNo++);
        for (int i = 0; i < cells.length; i++) {
            Object v = cells[i];
            if (v == null) {
                continue;
            }
            Cell c = r.createCell(i);
            if (v instanceof BigDecimal d) {
                c.setCellValue(d.doubleValue());
                c.setCellStyle(number);
            } else if (v instanceof Number n) {
                c.setCellValue(n.doubleValue());
            } else {
                c.setCellValue(String.valueOf(v));
                if (header) {
                    c.setCellStyle(bold);
                }
            }
        }
    }

    public byte[] build() {
        try (XSSFWorkbook closing = wb; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            closing.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the workbook", e);
        }
    }
}
