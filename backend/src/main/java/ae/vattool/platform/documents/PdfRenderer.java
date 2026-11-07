package ae.vattool.platform.documents;

import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.HtmlUtils;

/**
 * Turns a document body into a PDF in English or Arabic. A draft carries the not-for-filing watermark
 * in a page margin box, which repeats on every page where a fixed element does not reliably do so.
 * Arabic needs a bundled font and bidirectional shaping, because the PDF base fonts have no Arabic glyphs.
 * The margin boxes hold running elements and not strings, because a string in a margin box skips shaping,
 * and an Arabic warning would print with its letters reversed and unjoined.
 */
public final class PdfRenderer {

    public static final String WATERMARK = "DRAFT RULE PACK: NOT FOR FILING";
    private static final String WATERMARK_AR = "مسودة حزمة القواعد: غير مخصصة للتقديم";
    private static final String ARABIC_FONT = "Amiri";
    private static final byte[] AMIRI_REGULAR = font("fonts/Amiri-Regular.ttf");
    private static final byte[] AMIRI_BOLD = font("fonts/Amiri-Bold.ttf");

    private PdfRenderer() {}

    public static String watermark(String lang) {
        return "ar".equals(lang) ? WATERMARK_AR : WATERMARK;
    }

    public static byte[] render(String title, String bodyHtml, String lang, boolean draft) {
        boolean arabic = "ar".equals(lang);
        String family = arabic ? "'" + ARABIC_FONT + "', sans-serif" : "sans-serif";
        String mark = watermark(lang);
        DocText text = DocText.of(lang);
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html").append(arabic ? " dir=\"rtl\" lang=\"ar\"" : " lang=\"en\"").append("><head><meta charset=\"UTF-8\"/><title>")
                .append(esc(title)).append("</title><style>");
        h.append("@page { size: A4; margin: 18mm 14mm; ");
        if (draft) {
            h.append("@top-center { content: element(run-head); } ");
        }
        h.append("@bottom-center { content: element(run-foot); } }\n");
        h.append(".run-head { position: running(run-head); color: #b00000; font-weight: bold; font-size: 11pt; text-align: center; }\n")
                .append(".run-foot { position: running(run-foot); font-size: 8pt; color: #555; text-align: center; }\n")
                .append(".page-no::after { content: counter(page); } .page-count::after { content: counter(pages); }\n");
        h.append("body { font-family: ").append(family).append("; font-size: ").append(arabic ? "10pt" : "9pt").append("; line-height: ").append(arabic ? "1.5" : "1.25").append("; color: #1a2030; }\n")
                .append("h1 { font-size: 16pt; margin: 0 0 4px 0; } h2 { font-size: 11pt; margin: 16px 0 6px 0; border-bottom: 1px solid #999; padding-bottom: 2px; }\n")
                .append("table { width: 100%; border-collapse: collapse; } tr { page-break-inside: avoid; }\n")
                .append("th, td { border-bottom: 1px solid #ccc; padding: 3px 5px; text-align: ").append(arabic ? "right" : "left").append("; vertical-align: top; }\n")
                .append("th { background: #eee; font-size: 8pt; } td.n, th.n { text-align: ").append(arabic ? "left" : "right").append("; white-space: nowrap; } td.n { font-family: sans-serif; }\n")
                .append("tr.total td { font-weight: bold; background: #f4f4f4; }\n")
                .append(".meta { color: #444; } .hash { font-family: monospace; font-size: 7pt; }\n")
                .append(".banner { border: 2px solid #b00; color: #b00; font-weight: bold; padding: 6px; margin-bottom: 8px; text-align: center; }\n")
                .append(".wm { position: fixed; top: 110mm; left: -10mm; width: 200mm; text-align: center; font-size: 34pt; font-weight: bold; color: #e8b4b4; transform: rotate(-35deg); z-index: -1; }\n")
                .append(".ok { color: #17693b; font-weight: bold; } .bad { color: #a4201f; font-weight: bold; }\n")
                .append(".ltr { direction: ltr; }\n</style></head><body>");
        if (draft) {
            h.append("<div class=\"run-head\">").append(mark).append("</div>");
        }
        h.append("<div class=\"run-foot\">").append(text.t("doc.page")).append(" <span class=\"page-no ltr\"></span> ").append(text.t("doc.of"))
                .append(" <span class=\"page-count ltr\"></span></div>");
        if (draft) {
            h.append("<div class=\"wm\">").append(mark).append("</div>");
            h.append("<div class=\"banner\">").append(mark).append(". ").append(text.t("doc.notSignedOff")).append("</div>");
        }
        h.append(bodyHtml).append("</body></html>");
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            // On in both languages, because an Arabic client name or description can appear in an English pack.
            // There the font is only a last resort, so English text stays in the base sans-serif.
            builder.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
            builder.useUnicodeBidiReorderer(new ICUBidiReorderer());
            Set<BaseRendererBuilder.FSFontUseCase> uses = arabic
                    ? EnumSet.of(BaseRendererBuilder.FSFontUseCase.DOCUMENT, BaseRendererBuilder.FSFontUseCase.FALLBACK_FINAL)
                    : EnumSet.of(BaseRendererBuilder.FSFontUseCase.FALLBACK_FINAL);
            builder.useFont(() -> stream(AMIRI_REGULAR), ARABIC_FONT, 400, BaseRendererBuilder.FontStyle.NORMAL, true, uses);
            builder.useFont(() -> stream(AMIRI_BOLD), ARABIC_FONT, 700, BaseRendererBuilder.FontStyle.NORMAL, true, uses);
            if (arabic) {
                builder.defaultTextDirection(BaseRendererBuilder.TextDirection.RTL);
            }
            builder.withHtmlContent(h.toString(), null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not build the PDF", ex);
        }
    }

    public static String esc(Object o) {
        return HtmlUtils.htmlEscape(String.valueOf(o), StandardCharsets.UTF_8.name());
    }

    /** Figures stay in Latin digits and left to right in both languages, as on the VAT201. */
    public static String aed(BigDecimal b) {
        return String.format(java.util.Locale.ROOT, "%,.2f", b);
    }

    private static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static byte[] font(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Bundled font is missing: " + path, e);
        }
    }
}
