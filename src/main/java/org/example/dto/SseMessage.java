package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class SseMessage {
    private String type;
    private String data;

    public static SseMessage content(String data) {
        var m = new SseMessage(); m.type = "content"; m.data = data; return m;
    }

    public static SseMessage error(String msg) {
        var m = new SseMessage(); m.type = "error"; m.data = msg; return m;
    }

    public static SseMessage done() {
        var m = new SseMessage(); m.type = "done"; return m;
    }
}
