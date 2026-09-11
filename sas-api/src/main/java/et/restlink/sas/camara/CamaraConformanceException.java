/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.camara;

public class CamaraConformanceException extends RuntimeException {

    private final int status;
    private final String code;

    public CamaraConformanceException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static CamaraConformanceException invalidArgument(String message) {
        return new CamaraConformanceException(400, "INVALID_ARGUMENT", message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
