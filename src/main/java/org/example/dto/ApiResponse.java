package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        var r = new ApiResponse<T>();
        r.code = 200; r.message = "success"; r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> fail(String msg) {
        var r = new ApiResponse<T>();
        r.code = 500; r.message = msg;
        return r;
    }
}
