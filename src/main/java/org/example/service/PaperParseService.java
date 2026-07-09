package org.example.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.dto.PaperMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

@Service
public class PaperParseService {

    private static final Logger log = LoggerFactory.getLogger(PaperParseService.class);

    private static final Pattern ABSTRACT = Pattern.compile(
            "(?:Abstract|ABSTRACT)\\s*[\\n\\r]+(.{50,}?(?:\\n\\s*.+){0,20}?)" +
            "(?=\\n\\s*(?:Keywords|KEYWORDS|Index Terms|1\\.\\s*Introduction|1\\s+Introduction|I\\.\\s*INTRODUCTION))",
            Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final Pattern KEYWORDS = Pattern.compile(
            "(?:Keywords|KEYWORDS|Index Terms)\\s*[\\n\\r]*[-:：]?\\s*(.+?)(?=\\n\\s*(?:1\\.|1\\s+|I\\.))",
            Pattern.MULTILINE | Pattern.DOTALL);

    /** 提取 PDF 纯文本 */
    public String extractText(String pdfPath) throws IOException {
        var file = new File(pdfPath);
        if (!file.exists()) throw new IllegalArgumentException("PDF 文件不存在: " + pdfPath);
        try (var doc = Loader.loadPDF(file)) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setParagraphStart("\n\n");
            var text = stripper.getText(doc);
            log.info("PDF 提取完成: {} ({} 字符)", pdfPath, text.length());
            return text;
        }
    }

    /** 提取论文元数据：优先读 PDF 属性，失败后用正则从首页解析 */
    public PaperMetadata extractMetadata(String pdfPath, String fullText) {
        var meta = new PaperMetadata();

        try (var doc = Loader.loadPDF(new File(pdfPath))) {
            var info = doc.getDocumentInformation();
            if (info.getTitle() != null && !info.getTitle().isBlank()) meta.setTitle(info.getTitle().trim());
            if (info.getAuthor() != null && !info.getAuthor().isBlank()) meta.setAuthors(info.getAuthor().trim());
        } catch (IOException e) {
            log.warn("读取 PDF 属性失败: {}", e.getMessage());
        }

        // 标题/作者为空时从首页文字猜测
        if (meta.getTitle() == null || meta.getTitle().isBlank()) meta.setTitle(guessTitle(fullText));
        if (meta.getAuthors() == null || meta.getAuthors().isBlank()) meta.setAuthors(guessAuthors(fullText));
        meta.setPaperAbstract(extractAbstract(fullText));
        meta.setYear(extractYear(fullText));
        meta.setKeywords(extractKeywords(fullText));

        log.info("元数据: 标题={}, 作者={}, 年份={}", meta.getTitle(), meta.getAuthors(), meta.getYear());
        return meta;
    }

    // ===================== 启发式提取 =====================

    private String guessTitle(String text) {
        if (text == null || text.isBlank()) return null;
        var firstPage = text.substring(0, Math.min(2000, text.length())).replaceAll("\\s+", " ").trim();
        var title = new StringBuilder();
        for (var line : firstPage.split("\\n")) {
            var t = line.trim();
            if (t.length() < 10 || t.matches("^\\d+$") || t.toLowerCase().startsWith("abstract")) {
                if (!title.isEmpty()) break; else continue;
            }
            if (!title.isEmpty()) {
                if (t.toLowerCase().startsWith("abstract") || t.matches("^\\d+\\.\\s+.*") ||
                    t.toLowerCase().startsWith("introduction") || t.toLowerCase().startsWith("1 ")) break;
                title.append(" ");
            }
            title.append(t);
            if (title.length() > 20 && t.endsWith(".")) break;
            if (title.toString().split("\\s+").length > 25) break;
        }
        var s = title.toString().trim();
        return s.length() >= 10 ? s : null;
    }

    private String guessAuthors(String text) {
        if (text == null || text.isBlank()) return null;
        for (var line : text.substring(0, Math.min(3000, text.length())).split("\\n")) {
            var t = line.trim();
            if (t.length() > 200) continue;
            if (t.matches(".*[a-z]{2,},.*[A-Z][a-z]+.*") || t.contains(" and ") || t.contains(" & ") ||
                (t.contains(",") && t.matches(".*[A-Z]\\.\\s*[A-Z][a-z]+.*")))
                return t.replaceAll("[*†‡§¶#]", "").trim();
        }
        return null;
    }

    private String extractAbstract(String text) {
        if (text == null || text.isBlank()) return null;
        var m = ABSTRACT.matcher(text);
        if (m.find()) { var a = m.group(1).trim().replaceAll("\\s+", " ").trim(); if (a.length() > 50) return a; }
        int i = text.toLowerCase().indexOf("abstract");
        if (i >= 0) return text.substring(i + 8, Math.min(i + 2008, text.length())).replaceAll("\\s+", " ").trim();
        return null;
    }

    private Integer extractYear(String text) {
        if (text == null || text.isBlank()) return null;
        var m = YEAR.matcher(text.substring(0, Math.min(4000, text.length())));
        Integer earliest = null;
        while (m.find()) { int y = Integer.parseInt(m.group()); if (y >= 1950 && y <= 2026 && (earliest == null || y < earliest)) earliest = y; }
        return earliest;
    }

    private String extractKeywords(String text) {
        if (text == null || text.isBlank()) return null;
        var m = KEYWORDS.matcher(text);
        return m.find() ? m.group(1).trim().replaceAll("\\s+", " ").replaceAll("\\n", "; ") : null;
    }
}
