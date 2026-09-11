/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.camara;

public record CamaraErrorInfo(int status, String code, String message) {

    public static final String CODE_INVALID_ARGUMENT = "INVALID_ARGUMENT";

    public static CamaraErrorInfo invalidArgument(String message) {
        return new CamaraErrorInfo(400, CODE_INVALID_ARGUMENT, message);
    }
}
