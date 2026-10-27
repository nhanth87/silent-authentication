/*
 * CAMARA NV v2 bank backend SDK — Restlink (Ethiopia).
 * Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.nvbackend;

import java.util.Map;

/**
 * A refusal from the operator, in either of its two shapes: an OAuth error
 * ({@code {"error","error_description"}}) or a CAMARA error
 * ({@code {"status","code","message"}}). {@link #code()} is the OAuth error or the
 * CAMARA code. Every instance means "not verified": fall back.
 */
public class NvApiException extends RuntimeException {

    private final int status;
    private final String code;

    public NvApiException(int status, String code, String message) {
        super(code + (status > 0 ? " (HTTP " + status + ")" : "") + (message == null ? "" : ": " + message));
        this.status = status;
        this.code = code;
    }

    static NvApiException of(int status, Map<String, String> body) {
        if (body.get("error") != null) {
            return new NvApiException(status, body.get("error"), body.get("error_description"));
        }
        if (body.get("code") != null) {
            return new NvApiException(status, body.get("code"), body.get("message"));
        }
        return new NvApiException(status, "unexpected_response", null);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
