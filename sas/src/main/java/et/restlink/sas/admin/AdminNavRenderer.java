/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.admin;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class AdminNavRenderer {

    public static String adminNavLinks(AdminAuthService.Principal who, boolean loggedIn) {
        String auth = loggedIn
                ? "<a class=\"hover:text-signal\" href=\"/admin/logout\">Logout</a>"
                : "<a class=\"hover:text-signal\" href=\"/admin/login\">Login</a>";
        if (who != null && who.isTenantScoped()) {
            return """
                    <a class="hover:text-signal" href="/admin">Dashboard</a>
                    <a class="hover:text-signal" href="/admin/http">HTTP</a>
                    <a class="hover:text-signal" href="/admin/diameter">Diameter</a>
                    <a class="hover:text-signal" href="/admin/cdr">CDR</a>
                    %s
                    """.formatted(auth).trim();
        }
        return """
                <a class="hover:text-signal" href="/admin">Dashboard</a>
                <a class="hover:text-signal" href="/admin/ss7">SS7</a>
                <a class="hover:text-signal" href="/admin/http">HTTP</a>
                <a class="hover:text-signal" href="/admin/diameter">Diameter</a>
                <a class="hover:text-signal" href="/admin/cdr">CDR</a>
                <a class="hover:text-signal" href="/admin/tenants">Tenants</a>
                <a class="hover:text-signal" href="/admin/users">Users</a>
                %s
                """.formatted(auth).trim();
    }

    public Map<String, String> adminPageVars(AdminAuthService.Principal who, boolean loggedIn,
                                             Map<String, String> extra) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("{{NAV_LINKS}}", adminNavLinks(who, loggedIn));
        m.put("{{AUTH_NAV}}", loggedIn
                ? "<a class=\"hover:text-signal\" href=\"/admin/logout\">Logout</a>"
                : "<a class=\"hover:text-signal\" href=\"/admin/login\">Login</a>");
        m.put("{{SUCCESS_BANNER}}", "");
        m.put("{{NOTICE}}", "");
        m.put("{{ERROR}}", "");
        m.put("{{TENANT_OPTS}}", "");
        if (extra != null) {
            m.putAll(extra);
        }
        return m;
    }
}