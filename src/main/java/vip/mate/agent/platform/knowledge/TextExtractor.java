package vip.mate.agent.platform.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 文档文本抽取。MVP 支持 txt / md / csv / json / html / log / pdf。
 */
@Slf4j
@Component
public class TextExtractor {

    private static final long MAX_TEXT_CHARS = 2_000_000L;

    public String extract(String filename, String mime, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String text;
        if (name.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(mime)) {
            text = extractPdf(bytes);
        } else if (isTextFile(name, mime)) {
            text = new String(bytes, StandardCharsets.UTF_8);
            if (name.endsWith(".html") || name.endsWith(".htm")) {
                text = stripHtml(text);
            }
        } else {
            throw new IllegalArgumentException("暂不支持该文件类型，请上传 txt / md / csv / json / html / log / pdf");
        }
        text = clean(text);
        if (text.isBlank()) {
            throw new IllegalArgumentException("未能从文件中提取到文本内容（扫描版 PDF 需要先做 OCR）");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, (int) MAX_TEXT_CHARS);
            log.warn("[kb] 文档 {} 文本超过 {} 字符，已截断", filename, MAX_TEXT_CHARS);
        }
        return text;
    }

    private boolean isTextFile(String name, String mime) {
        if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown")
                || name.endsWith(".csv") || name.endsWith(".json") || name.endsWith(".log")
                || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xml")
                || name.endsWith(".yml") || name.endsWith(".yaml")) {
            return true;
        }
        return mime != null && (mime.startsWith("text/")
                || mime.equalsIgnoreCase("application/json")
                || mime.equalsIgnoreCase("application/xml"));
    }

    private String extractPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (Exception e) {
            log.warn("[kb] PDF 解析失败", e);
            throw new IllegalArgumentException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    private String stripHtml(String html) {
        return html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private String clean(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
        // 去掉每行多余空白，但保留段落之间的空行
        value = value.replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("(?m)[ ]{2,}", " ")
                .replaceAll("(?m)^[ ]+", "")
                .replaceAll("(?m)[ ]+$", "");
        value = value.replaceAll("\\n{3,}", "\n\n");
        return value.trim();
    }
}
