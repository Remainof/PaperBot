package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class DocumentChunkService {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkService.class);

    // 论文章节标题: "1. Introduction", "2. Method", "I. INTRODUCTION" 等
    private static final Pattern SECTION = Pattern.compile(
            "\\n\\s*(\\d+\\.?\\s+[A-Z][A-Za-z\\s/&,-]+|[A-Z]+\\.\\s+[A-Z][A-Za-z\\s/&,-]+)\\s*\\n");
    private static final Pattern REF_HEADING = Pattern.compile(
            "\\n\\s*(References|REFERENCES|Bibliography|BIBLIOGRAPHY|参考文献)\\s*\\n");
    private static final Pattern REF_ENTRY = Pattern.compile(
            "(?:(?:\\[\\d+\\]|\\d+\\.\\s|\\(\\d+\\))\\s*.{20,}?)(?=(?:\\[\\d+\\]|\\d+\\.\\s|\\(\\d+\\)|\\Z))",
            Pattern.DOTALL);
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    @Autowired private DocumentChunkConfig cfg;

    // ===================== 入口 =====================

    public List<DocumentChunk> chunkDocument(String content, String filePath) {
        return chunkByHeadings(content);
    }

    public List<DocumentChunk> chunkDocument(String content, String filePath, boolean isPaper) {
        return isPaper ? chunkPaper(content) : chunkByHeadings(content);
    }

    // ===================== 论文分片 =====================

    private List<DocumentChunk> chunkPaper(String content) {
        if (content == null || content.isBlank()) return List.of();

        // 1. 分离参考文献
        var refMatcher = REF_HEADING.matcher(content);
        String mainBody = content, refSection = null;
        if (refMatcher.find()) {
            refSection = content.substring(refMatcher.end()).trim();
            mainBody = content.substring(0, refMatcher.start()).trim();
        }

        // 2. 正文按章节切分
        var sections = splitBySection(mainBody);
        var chunks = new ArrayList<DocumentChunk>();
        int idx = 0;
        for (var sec : sections) {
            var cs = chunkSection(sec, idx);
            cs.forEach(c -> c.setTitle(sec.title));
            chunks.addAll(cs);
            idx += cs.size();
        }

        // 3. 参考文献单独分块
        if (refSection != null && !refSection.isBlank()) {
            chunks.addAll(chunkReferences(refSection, idx));
        }
        return chunks;
    }

    /** 按编号章节标题分割正文 */
    private List<Section> splitBySection(String text) {
        var m = SECTION.matcher(text);
        var sections = new ArrayList<Section>();
        int prev = 0;
        String title = null;
        while (m.find()) {
            if (prev < m.start()) {
                var c = text.substring(prev, m.start()).trim();
                if (!c.isEmpty()) sections.add(new Section(title, c));
            }
            title = m.group(1).trim();
            prev = m.start();
        }
        if (prev < text.length()) {
            var c = text.substring(prev).trim();
            if (!c.isEmpty()) sections.add(new Section(title, c));
        }
        if (sections.isEmpty()) sections.add(new Section(null, text));
        return sections;
    }

    /** 参考文献按引用编号切分 */
    private List<DocumentChunk> chunkReferences(String text, int startIdx) {
        var entries = new ArrayList<String>();
        var m = REF_ENTRY.matcher(text);
        while (m.find()) { var e = m.group().trim(); if (e.length() > 30) entries.add(e); }

        // 编号模式没匹配到，按空行切分
        if (entries.isEmpty()) {
            for (var p : text.split("\\n\\s*\\n")) {
                var t = p.trim().replaceAll("\\s+", " ");
                if (t.length() > 30) entries.add(t);
            }
        }

        // 条目太多时 5 条合并一批
        if (entries.size() > 50) {
            var merged = new ArrayList<String>();
            var buf = new StringBuilder();
            int cnt = 0;
            for (var e : entries) {
                buf.append(e).append("\n");
                if (++cnt >= 5) { merged.add(buf.toString().trim()); buf.setLength(0); cnt = 0; }
            }
            if (!buf.isEmpty()) merged.add(buf.toString().trim());
            entries = merged;
        }

        var chunks = new ArrayList<DocumentChunk>();
        int idx = startIdx;
        for (var e : entries) {
            if (e.length() <= 10) continue;
            var c = new DocumentChunk(e, 0, e.length(), idx++);
            c.setTitle("References");
            chunks.add(c);
        }
        return chunks;
    }

    // ===================== 普通文档（Markdown 标题） =====================

    private List<DocumentChunk> chunkByHeadings(String content) {
        if (content == null || content.isBlank()) return List.of();
        var m = MARKDOWN_HEADING.matcher(content);
        var sections = new ArrayList<Section>();
        int prev = 0;
        String title = null;
        while (m.find()) {
            if (prev < m.start()) {
                var c = content.substring(prev, m.start()).trim();
                if (!c.isEmpty()) sections.add(new Section(title, c));
            }
            title = m.group(2).trim();
            prev = m.start();
        }
        if (prev < content.length()) {
            var c = content.substring(prev).trim();
            if (!c.isEmpty()) sections.add(new Section(title, c));
        }
        if (sections.isEmpty()) sections.add(new Section(null, content));

        var chunks = new ArrayList<DocumentChunk>();
        int idx = 0;
        for (var s : sections) { var cs = chunkSection(s, idx); chunks.addAll(cs); idx += cs.size(); }
        return chunks;
    }

    // ===================== 通用分片逻辑 =====================

    /** 将单个章节切分为不超过 max-size 的块，带 overlap */
    private List<DocumentChunk> chunkSection(Section sec, int startIdx) {
        if (sec.content.length() <= cfg.getMaxSize()) {
            var c = new DocumentChunk(sec.content, 0, sec.content.length(), startIdx);
            c.setTitle(sec.title);
            return List.of(c);
        }

        var chunks = new ArrayList<DocumentChunk>();
        var paragraphs = sec.content.split("\n\n+");
        var buf = new StringBuilder();
        int idx = startIdx;

        for (var p : paragraphs) {
            var t = p.trim();
            if (t.isEmpty()) continue;

            if (!buf.isEmpty() && buf.length() + t.length() > cfg.getMaxSize()) {
                var cc = buf.toString().trim();
                chunks.add(chunk(cc, sec.title, idx++));
                buf = new StringBuilder(overlapText(cc));
            }
            buf.append(t).append("\n\n");
        }
        if (!buf.isEmpty()) chunks.add(chunk(buf.toString().trim(), sec.title, idx));
        return chunks;
    }

    private DocumentChunk chunk(String content, String title, int idx) {
        var c = new DocumentChunk(content, 0, content.length(), idx);
        c.setTitle(title);
        return c;
    }

    /** 取段尾 overlap 字（优先在句号处断开） */
    private String overlapText(String text) {
        int len = Math.min(cfg.getOverlap(), text.length());
        if (len <= 0) return "";
        var tail = text.substring(text.length() - len);
        int cut = Math.max(tail.lastIndexOf('。'), Math.max(tail.lastIndexOf('？'), tail.lastIndexOf('！')));
        return (cut > len / 2 ? tail.substring(cut + 1) : tail).trim();
    }

    // ===================== 内部数据结构 =====================

    record Section(String title, String content) {}
}
