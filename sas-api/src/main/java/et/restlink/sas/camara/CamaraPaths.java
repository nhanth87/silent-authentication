/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.camara;

public final class CamaraPaths {

    public static final String API_ROOT = "/camara";

    private static final String NUMBER_VERIFICATION_PREFIX = "/number-verification/";
    private static final String SIM_SWAP_PREFIX = "/sim-swap/";
    private static final String ONE_TIME_PASSWORD_SMS_PREFIX = "/one-time-password-sms/";

    private CamaraPaths() {
    }

    public static String normalize(String rawPath) {
        if (rawPath == null) {
            return "/";
        }
        String path = rawPath.trim();
        if (path.isEmpty()) {
            return "/";
        }
        while (path.startsWith("//")) {
            path = path.substring(1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    public static boolean isCamaraPath(String rawPath) {
        String path = normalize(rawPath);
        return path.equals(API_ROOT)
                || path.startsWith(API_ROOT + "/")
                || path.startsWith(NUMBER_VERIFICATION_PREFIX)
                || path.startsWith(SIM_SWAP_PREFIX)
                || path.startsWith(ONE_TIME_PASSWORD_SMS_PREFIX);
    }

    public static String withoutApiRoot(String rawPath) {
        String path = normalize(rawPath);
        if (path.equals(API_ROOT)) {
            return "/";
        }
        if (path.startsWith(API_ROOT + "/")) {
            return path.substring(API_ROOT.length());
        }
        return path;
    }
}
