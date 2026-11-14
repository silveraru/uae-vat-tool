package ae.vattool.demo;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One-page PDFs with plain text, for the demo's evidence files: acknowledgements, bills of lading and
 * the like. Written by hand so hundreds take no time, and each carries a footer saying it is synthetic,
 * so nobody mistakes a demo file for a real one.
 */
final class Pdf {

    static final String FOOTER = "Synthetic document made by the demo data loader. It was not issued by any authority, bank or company.";

    private Pdf() {}

    static byte[] page(String title, List<String> lines) {
        StringBuilder content = new StringBuilder();
        content.append("BT /F2 15 Tf 56 780 Td (").append(escape(title)).append(") Tj ET\n");
        int y = 748;
        for (String line : lines) {
            for (String part : wrap(line, 92)) {
                if (y < 70) {
                    break;
                }
                content.append("BT /F1 10 Tf 56 ").append(y).append(" Td (").append(escape(part)).append(") Tj ET\n");
                y -= 15;
            }
        }
        content.append("BT /F1 7 Tf 56 36 Td (").append(escape(FOOTER)).append(") Tj ET\n");
        byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);

        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n");
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        offsets.add(out.size());
        write(out, "6 0 obj\n<< /Length " + stream.length + " >>\nstream\n");
        out.writeBytes(stream);
        write(out, "endstream\nendobj\n");
        int xref = out.size();
        StringBuilder table = new StringBuilder("xref\n0 7\n0000000000 65535 f \n");
        for (int offset : offsets) {
            table.append(String.format("%010d 00000 n \n", offset));
        }
        write(out, table + "trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** PDF strings need their brackets and backslashes escaped, and the base fonts only cover Latin-1. */
    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\\' || c == '(' || c == ')') {
                b.append('\\').append(c);
            } else if (c < 32 || c > 255) {
                b.append(c == '–' || c == '—' ? '-' : '?');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static List<String> wrap(String line, int width) {
        List<String> out = new ArrayList<>();
        String rest = line == null ? "" : line;
        while (rest.length() > width) {
            int cut = rest.lastIndexOf(' ', width);
            if (cut <= 0) {
                cut = width;
            }
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut).stripLeading();
        }
        out.add(rest);
        return out;
    }
}
