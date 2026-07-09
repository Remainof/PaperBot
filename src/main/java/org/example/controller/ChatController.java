package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.dto.ChatRequest;
import org.example.dto.SseMessage;
import org.example.service.ChatService;
import org.example.service.RagService;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final int MAX_HISTORY_ROUNDS = 6;

    @Autowired private ChatService chatService;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    // ====================== 非流式问答 ======================

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chat(@RequestBody ChatRequest req) {
        if (req.getQuestion() == null || req.getQuestion().isBlank()) {
            return ResponseEntity.ok(ApiResponse.fail("问题不能为空"));
        }
        try {
            var session = getSession(req.getId());
            var answer = chatService.executeRag(req.getQuestion(), session.history());
            session.add(req.getQuestion(), answer);
            return ResponseEntity.ok(ApiResponse.ok(Map.of("answer", answer)));
        } catch (Exception e) {
            log.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.fail(e.getMessage()));
        }
    }

    // ====================== 流式问答 ======================

    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest req) {
        var emitter = new SseEmitter(300_000L);

        if (req.getQuestion() == null || req.getQuestion().isBlank()) {
            sendError(emitter, "问题不能为空");
            return emitter;
        }

        executor.execute(() -> {
            try {
                var session = getSession(req.getId());
                chatService.executeRagStream(req.getQuestion(), session.history(), new RagService.StreamCallback() {
                    final StringBuilder buf = new StringBuilder();

                    @Override public void onContentChunk(String chunk) {
                        buf.append(chunk);
                        send(emitter, SseMessage.content(chunk));
                    }

                    @Override public void onComplete(String full, String _reasoning) {
                        session.add(req.getQuestion(), full);
                        send(emitter, SseMessage.done());
                        emitter.complete();
                    }

                    @Override public void onError(Exception e) {
                        log.error("流式问答异常", e);
                        sendError(emitter, e.getMessage());
                        emitter.completeWithError(e);
                    }

                    @Override public void onSearchResults(List<VectorSearchService.SearchResult> _results) {}
                    @Override public void onReasoningChunk(String _chunk) {}
                });
            } catch (Exception e) {
                sendError(emitter, e.getMessage());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    // ====================== 会话管理 ======================

    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clear(@RequestBody Map<String, String> body) {
        var sid = body.get("id");
        if (sid == null || sid.isBlank()) return ResponseEntity.ok(ApiResponse.fail("会话 ID 不能为空"));
        var s = sessions.get(sid);
        if (s != null) { s.clear(); return ResponseEntity.ok(ApiResponse.ok("已清空")); }
        return ResponseEntity.ok(ApiResponse.fail("会话不存在"));
    }

    @GetMapping("/chat/session/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sessionInfo(@PathVariable String id) {
        var s = sessions.get(id);
        if (s == null) return ResponseEntity.ok(ApiResponse.fail("会话不存在"));
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "sessionId", id, "rounds", s.rounds(), "createdAt", s.createdAt
        )));
    }

    // ====================== 内部 ======================

    private Session getSession(String id) {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        return sessions.computeIfAbsent(id, Session::new);
    }

    private void send(SseEmitter em, Object data) {
        try { em.send(SseEmitter.event().name("message").data(data, MediaType.APPLICATION_JSON)); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    private void sendError(SseEmitter em, String msg) {
        try { em.send(SseEmitter.event().name("message").data(SseMessage.error(msg), MediaType.APPLICATION_JSON)); em.complete(); }
        catch (IOException _e) { em.completeWithError(new RuntimeException(msg)); }
    }

    // ====================== 会话 ======================

    static class Session {
        final String id;
        final long createdAt;
        private final List<Map<String, String>> messages = new ArrayList<>();

        Session(String id) { this.id = id; this.createdAt = System.currentTimeMillis(); }

        synchronized void add(String q, String a) {
            messages.add(Map.of("role", "user", "content", q));
            messages.add(Map.of("role", "assistant", "content", a));
            while (messages.size() > MAX_HISTORY_ROUNDS * 2) {
                messages.remove(0);
                if (!messages.isEmpty()) messages.remove(0);
            }
        }

        synchronized List<Map<String, String>> history() { return List.copyOf(messages); }
        synchronized void clear() { messages.clear(); }
        synchronized int rounds() { return messages.size() / 2; }
    }
}
