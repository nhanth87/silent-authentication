/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.admin;

import et.restlink.sas.cdr.SasCdrService;
import et.restlink.sas.config.SasAdminRuntimeConfig;
import et.restlink.sas.bootstrap.SasBootstrap;
import et.restlink.sas.persist.SasAppUserEntity;
import et.restlink.sas.persist.SasCdrEntity;
import et.restlink.sas.persist.SasTenantEntity;
import et.restlink.sas.security.ApiKeyGenerator;
import et.restlink.sas.tenant.AppUserService;
import et.restlink.sas.tenant.AdminUserService;
import et.restlink.sas.tenant.TenantService;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * SAS admin dashboard HTTP surface. Routing is intentionally framework-free so
 * the same handler can be mounted behind either the Quarkus REST adapter or the
 * micro-jainslee HTTP RA; it only sees (method, path, headers, query, body).
 */
@ApplicationScoped
public class AdminHttpHandler {

    private static final Logger LOG = LogManager.getLogger(AdminHttpHandler.class);

    @Inject SasCdrService cdr;
    @Inject AdminAuthService adminAuth;
    @Inject AdminPageRenderer pages;
    @Inject AdminNavRenderer nav;
    @Inject SasAdminRuntimeConfig runtimeConfig;
    @Inject SasBootstrap bootstrap;
    @Inject TenantService tenants;
    @Inject AppUserService appUsers;
    @Inject AdminUserService users;

    @ConfigProperty(name = "sas.admin.cookie-secure", defaultValue = "false")
    boolean cookieSecure = false;

    public record HttpReply(int status, String contentType, byte[] body, Map<String, String> headers) {
        public static final String SET_COOKIE_SEP = "\n";

        public static HttpReply html(String html) {
            return html(200, html);
        }

        public static HttpReply html(int status, String html) {
            return new HttpReply(status, "text/html; charset=utf-8",
                    html.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        public static HttpReply json(int status, Object node) {
            try {
                return new HttpReply(status, "application/json",
                        new ObjectMapper().writeValueAsBytes(node), Map.of());
            } catch (Exception e) {
                return text(500, "serialize");
            }
        }

        public static HttpReply text(int status, String body) {
            return new HttpReply(status, "text/plain; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        public static HttpReply bytes(String contentType, byte[] body) {
            return new HttpReply(200, contentType, body == null ? new byte[0] : body, Map.of());
        }

        public static HttpReply notFound() {
            return text(404, "not found");
        }

        public static HttpReply redirect(String location) {
            return new HttpReply(302, "text/plain; charset=utf-8",
                    ("Redirect: " + location).getBytes(StandardCharsets.UTF_8),
                    Map.of("Location", location));
        }

        public HttpReply addSetCookie(String cookie) {
            Map<String, String> h = new LinkedHashMap<>(headers == null ? Map.of() : headers);
            String existing = h.get("Set-Cookie");
            h.put("Set-Cookie", existing == null ? cookie : existing + SET_COOKIE_SEP + cookie);
            return new HttpReply(status, contentType, body, Map.copyOf(h));
        }
    }

    public Optional<HttpReply> tryHandle(String method, String path,
                                         Map<String, String> headers,
                                         Map<String, String> query,
                                         String body, Object uploadsIgnored) {
        if (path == null) {
            return Optional.empty();
        }
        String p = path.startsWith("/") ? path : "/" + path;
        int qmark = p.indexOf('?');
        if (qmark >= 0) {
            p = p.substring(0, qmark);
        }
        if (p.equals("/health") || p.equals("/healthz")) {
            return Optional.of(HttpReply.json(200, Map.of("status", "UP")));
        }
        Optional<AdminAuthService.Principal> principal = adminAuth.authenticate(headers, query);
        if (!(p.startsWith("/admin") || p.equals("/"))) {
            return Optional.empty();
        }
        boolean sessionOk = principal.isPresent() && principal.get().fromSession();
        if (p.equals("/")) {
            return Optional.of(HttpReply.redirect(sessionOk ? "/admin" : "/admin/login"));
        }
        if (p.startsWith("/admin/static/")) {
            try {
                return Optional.of(pages.staticResource(p.substring("/admin/static/".length())));
            } catch (Exception e) {
                return Optional.of(HttpReply.notFound());
            }
        }
        if (p.equals("/admin/login")) {
            if ("POST".equalsIgnoreCase(method)) {
                return Optional.of(handleLogin(body));
            }
            return Optional.of(page("login.html", null, false));
        }
        if (p.equals("/admin/logout")) {
            return Optional.of(HttpReply.redirect("/admin/login")
                    .addSetCookie(SignedSessionCookie.clearCookieHeader(cookieSecure))
                    .addSetCookie(SignedSessionCookie.clearCsrfCookieHeader(cookieSecure)));
        }
        if (wantsShell(method, headers, p)) {
            if (principal.isEmpty()) {
                return Optional.of(HttpReply.redirect("/admin/login"));
            }
            Optional<HttpReply> shell = serveShell(p, principal.orElse(null));
            if (shell.isPresent()) {
                return shell;
            }
        }
        if (principal.isEmpty()) {
            return Optional.of(HttpReply.text(401, "unauthorized"));
        }
        AdminAuthService.Principal who = principal.get();
        if ("POST".equalsIgnoreCase(method) && !sessionCsrfOk(who, headers)) {
            return Optional.of(HttpReply.text(403, "Session expired. Reload and sign in again."));
        }
        if (p.equals("/admin/status") || p.equals("/admin/status.json")) {
            return Optional.of(HttpReply.json(200, statusSnapshot()));
        }
        if (p.equals("/admin/status/partial")) {
            return Optional.of(HttpReply.html(dashboardStatusHtml()));
        }
        if ("GET".equalsIgnoreCase(method) && p.equals("/admin/cdr/partial")) {
            return Optional.of(HttpReply.html(cdrRows(who)));
        }
        if ("GET".equalsIgnoreCase(method) && p.equals("/admin/tenants/partial")) {
            return Optional.of(HttpReply.html(tenantRows()));
        }
        if ("GET".equalsIgnoreCase(method) && p.equals("/admin/users/partial")) {
            return Optional.of(HttpReply.html(userRows() + appUserRows()));
        }
        if ("POST".equalsIgnoreCase(method) && p.equals("/admin/tenants") && "ADMIN".equals(who.role())) {
            return Optional.of(handleTenantsPost(body, headers));
        }
        if ("POST".equalsIgnoreCase(method) && p.equals("/admin/users") && "ADMIN".equals(who.role())) {
            return Optional.of(handleUsersPost(body, headers));
        }
        return Optional.of(HttpReply.notFound());
    }

    static final String LOGIN_ERROR = "Invalid username or password";

    private HttpReply handleLogin(String body) {
        Map<String, String> f = form(body);
        Optional<String> tok = adminAuth.login(f.get("username"), f.get("password"));
        if (tok.isEmpty()) {
            return page("login.html", null, false, Map.of("{{ERROR}}", LOGIN_ERROR));
        }
        String csrf = SignedSessionCookie.csrfToken(adminAuth.sessionHmacSecret(), tok.get());
        return HttpReply.redirect("/admin")
                .addSetCookie(SignedSessionCookie.setCookieHeader(tok.get(), cookieSecure))
                .addSetCookie(SignedSessionCookie.setCsrfCookieHeader(csrf, cookieSecure));
    }

    private Optional<HttpReply> serveShell(String p, AdminAuthService.Principal who) {
        return switch (p) {
            case "/admin", "/admin/" -> Optional.of(page("index.html", who, true));
            case "/admin/http" -> Optional.of(page("http.html", who, true,
                    Map.of("{{HTTP_DETAIL}}", AdminPageRenderer.esc(httpDetail()))));
            case "/admin/cdr" -> Optional.of(page("cdr.html", who, true,
                    Map.of("{{CDR_ROWS}}", cdrRows(who))));
            case "/admin/tenants" -> Optional.of(page("tenants.html", who, true,
                    Map.of("{{TENANT_ROWS}}", tenantRows())));
            case "/admin/users" -> Optional.of(page("users.html", who, true, Map.of(
                    "{{USER_ROWS}}", userRows(),
                    "{{APP_USER_ROWS}}", appUserRows(),
                    "{{TENANT_OPTS}}", tenantOpts())));
            default -> Optional.empty();
        };
    }

    private HttpReply page(String name, AdminAuthService.Principal who, boolean loggedIn) {
        return page(name, who, loggedIn, Map.of());
    }

    private HttpReply page(String name, AdminAuthService.Principal who, boolean loggedIn,
                           Map<String, String> extra) {
        try {
            return pages.pageWith(name, nav.adminPageVars(who, loggedIn, extra));
        } catch (Exception e) {
            LOG.error("[admin] cannot render {}: {}", name, e.toString());
            return HttpReply.html(AdminPageRenderer.fallbackDashboard());
        }
    }

    private HttpReply handleTenantsPost(String body, Map<String, String> headers) {
        Map<String, String> f = form(body);
        try {
            tenants.upsert(f.get("tenantId"), f.get("displayName"),
                    parseInt(f.get("networkId"), 0), true, f.get("adminApiKey"));
        } catch (IllegalArgumentException bad) {
            return HttpReply.html(400, "<tr><td colspan=\"4\" class=\"admin-empty\">"
                    + AdminPageRenderer.esc(bad.getMessage()) + "</td></tr>");
        }
        if (hxRequest(headers)) {
            return HttpReply.html(tenantRows());
        }
        return HttpReply.redirect("/admin/tenants");
    }

    private HttpReply handleUsersPost(String body, Map<String, String> headers) {
        Map<String, String> f = form(body);
        String action = f.getOrDefault("action", "create");
        boolean enabled = !"false".equalsIgnoreCase(f.getOrDefault("enabled", "true"));
        try {
            if ("create-appkey".equals(action)) {
                AppUserService.Created created = appUsers.create(f.get("username"), f.get("tenantId"));
                if (hxRequest(headers)) {
                    return HttpReply.html(renderAppKey(created));
                }
                return HttpReply.redirect("/admin/users");
            }
            if ("delete".equals(action)) {
                users.delete(f.get("username"));
            } else if ("update".equals(action)) {
                users.update(f.get("username"), f.get("password"), f.get("role"),
                        f.get("tenantId"), f.get("displayName"), enabled);
            } else {
                users.create(f.get("username"), f.get("password"), f.get("role"),
                        f.get("tenantId"), f.get("displayName"), enabled);
            }
        } catch (IllegalArgumentException bad) {
            return HttpReply.html(400, "<tr><td colspan=\"6\" class=\"admin-empty\">"
                    + AdminPageRenderer.esc(bad.getMessage()) + "</td></tr>");
        }
        if (hxRequest(headers)) {
            return HttpReply.html(userRows());
        }
        return HttpReply.redirect("/admin/users");
    }

    static boolean sessionCsrfOk(AdminAuthService.Principal who, Map<String, String> headers) {
        if (who == null || !who.fromSession()) {
            return true;
        }
        String cookie = SignedSessionCookie.extractCookie(
                AdminAuthService.header(headers, "Cookie"), SignedSessionCookie.CSRF_COOKIE_NAME)
                .orElse("");
        String header = AdminAuthService.header(headers, SignedSessionCookie.CSRF_HEADER);
        return SignedSessionCookie.csrfMatches(cookie, header);
    }

    static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        for (String part : body.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            out.put(java.net.URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static boolean hxRequest(Map<String, String> headers) {
        if (headers == null) {
            return false;
        }
        for (var e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("HX-Request")
                    && "true".equalsIgnoreCase(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean wantsShell(String method, Map<String, String> headers, String path) {
        if (!"GET".equalsIgnoreCase(method)) {
            return false;
        }
        if (path.contains("/partial") || path.endsWith(".json") || path.equals("/admin/status")) {
            return false;
        }
        String accept = headers == null ? "" : String.valueOf(headers.getOrDefault("Accept", ""));
        return !accept.contains("application/json");
    }

    private Map<String, Object> statusSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("exposure.baseUrl", bootstrap == null ? "" : bootstrap.exposureBaseUrl());
        out.put("exposure.afId", bootstrap == null ? "" : bootstrap.afId());
        out.put("http.ra.port", runtimeConfig.getInt(SasAdminRuntimeConfig.Keys.HTTP_RA_PORT, 0));
        return out;
    }

    /**
     * Layered ADR §2: this gateway runs no MAP/Diameter stack, so there is nothing of the
     * core to configure here — only where the subscriber exposure service lives.
     */
    private String dashboardStatusHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"grid gap-4 sm:grid-cols-2\">");
        sb.append("<section class=\"rounded-lg border border-ink-line bg-ink-panel/80 p-4\">");
        sb.append("<p class=\"text-xs uppercase tracking-[0.25em] text-signal\">Subscriber exposure</p>");
        sb.append("<dl class=\"mt-2 space-y-1 text-sm\">");
        sb.append(dtDd("Entitlement server", bootstrap == null ? "" : bootstrap.exposureBaseUrl()));
        sb.append(dtDd("AF id", bootstrap == null ? "" : bootstrap.afId()));
        sb.append(dtDd("Signalling", "none in this binary (sas-ecs owns MAP/Diameter)"));
        sb.append("</dl></section>");
        sb.append("</div>");
        sb.append("<p class=\"mt-3 text-xs text-ink-mute\">HTTP RA port ")
                .append(runtimeConfig.getInt(SasAdminRuntimeConfig.Keys.HTTP_RA_PORT, 0))
                .append(" · /health · /admin/status.json</p>");
        return sb.toString();
    }

    private String httpDetail() {
        int port = runtimeConfig.getInt(SasAdminRuntimeConfig.Keys.HTTP_RA_PORT, 0);
        return "HTTP RA port " + port
                + " — lab accepts all origins; production enforces HTTPS + Bearer/API-key.";
    }

    private String cdrRows(AdminAuthService.Principal who) {
        List<SasCdrEntity> rows = cdr.recent(50);
        if (who != null && who.isTenantScoped()) {
            String tenantId = who.tenantId();
            rows = rows.stream()
                    .filter(r -> tenantId != null && tenantId.equals(r.tenantId))
                    .toList();
        }
        return renderCdrRows(rows);
    }

    static String renderCdrRows(List<SasCdrEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return "<tr><td colspan=\"7\" class=\"admin-empty\">"
                    + "<strong>No CDRs recorded</strong><span>Send a /verify request to populate this ledger.</span>"
                    + "</td></tr>";
        }
        StringBuilder sb = new StringBuilder();
        for (SasCdrEntity e : rows) {
            sb.append("<tr><td class=\"cdr-when\">").append(AdminPageRenderer.esc(e.recordedAt))
                    .append("</td><td class=\"cdr-corr\">").append(AdminPageRenderer.esc(e.correlationId))
                    .append("</td><td class=\"cdr-msisdn\">").append(AdminPageRenderer.esc(e.msisdn))
                    .append("</td><td>").append(AdminPageRenderer.esc(e.operation))
                    .append("</td><td><span class=\"cdr-status ")
                    .append(cdrStatusClass(e.status)).append("\">")
                    .append(AdminPageRenderer.esc(e.status)).append("</span></td>")
                    .append("<td class=\"cdr-detail-text\">").append(AdminPageRenderer.esc(e.detail))
                    .append("</td><td>").append(AdminPageRenderer.esc(e.tenantId))
                    .append("</td></tr>");
        }
        return sb.toString();
    }

    static String cdrStatusClass(String status) {
        if (status == null || status.isBlank()) {
            return "cdr-status--ok";
        }
        String u = status.toUpperCase(Locale.ROOT);
        if (u.contains("TIMED_OUT") || u.contains("TIMEOUT") || u.contains("TIME_OUT")) {
            return "cdr-status--timeout";
        }
        if (u.contains("FAIL") || u.contains("ERROR") || u.contains("ABORT")
                || u.contains("CANCEL")) {
            return "cdr-status--failed";
        }
        return "cdr-status--ok";
    }

    private String tenantRows() {
        StringBuilder sb = new StringBuilder();
        for (SasTenantEntity t : tenants.list()) {
            sb.append("<tr><td>").append(AdminPageRenderer.esc(t.tenantId))
                    .append("</td><td>").append(AdminPageRenderer.esc(t.displayName))
                    .append("</td><td>").append(t.networkId)
                    .append("</td><td>").append(AdminPageRenderer.esc(t.adminApiKey))
                    .append("</td></tr>");
        }
        return sb.isEmpty() ? "<tr><td colspan=\"4\">No tenants</td></tr>" : sb.toString();
    }

    private String userRows() {
        StringBuilder sb = new StringBuilder();
        for (var u : users.list()) {
            sb.append("<tr><td>").append(AdminPageRenderer.esc(u.username))
                    .append("</td><td>").append(AdminPageRenderer.esc(u.role))
                    .append("</td><td>").append(AdminPageRenderer.esc(u.tenantId))
                    .append("</td><td>").append(AdminPageRenderer.esc(u.displayName))
                    .append("</td><td>").append(u.enabled)
                    .append("</td></tr>");
        }
        return sb.isEmpty() ? "<tr><td colspan=\"5\">No admin users</td></tr>" : sb.toString();
    }

    private String appUserRows() {
        StringBuilder sb = new StringBuilder();
        for (SasAppUserEntity u : appUsers.list()) {
            sb.append("<tr><td>").append(AdminPageRenderer.esc(u.username))
                    .append("</td><td>").append(AdminPageRenderer.esc(u.tenantId))
                    .append("</td><td>").append(u.networkId)
                    .append("</td><td>").append(AdminPageRenderer.esc(u.apiKeyFp))
                    .append("</td><td>").append(u.enabled)
                    .append("</td></tr>");
        }
        return sb.isEmpty() ? "<tr><td colspan=\"5\">No app users</td></tr>" : sb.toString();
    }

    private String renderAppKey(AppUserService.Created c) {
        String fingerprint;
        try {
            fingerprint = ApiKeyGenerator.fingerprint(c.apiKey());
        } catch (RuntimeException ex) {
            fingerprint = "";
        }
        return "<div class=\"app-key-once mt-4 rounded-lg border border-signal/40 bg-signal/5 p-4\">"
                + "<p class=\"text-sm font-semibold uppercase tracking-wider text-signal\">API key — shown once</p>"
                + "<p class=\"mt-1 text-sm text-slate-300\">user <strong>"
                + AdminPageRenderer.esc(c.username()) + "</strong> · tenant "
                + AdminPageRenderer.esc(c.tenantId()) + " · networkId " + c.networkId()
                + " · fingerprint <code class=\"font-mono\">" + AdminPageRenderer.esc(fingerprint) + "</code></p>"
                + "<pre class=\"mt-3 overflow-x-auto rounded-md border border-ink-line bg-ink-panel p-3 font-mono text-xs text-slate-100\">"
                + AdminPageRenderer.esc(c.apiKey()) + "</pre>"
                + "<p class=\"mt-2 text-xs text-ink-mute\">Copy and store it now — only the bcrypt hash is persisted.</p>"
                + "</div>";
    }

    private String tenantOpts() {
        StringBuilder sb = new StringBuilder();
        sb.append("<option value=\"\"></option>");
        for (SasTenantEntity t : tenants.list()) {
            sb.append("<option value=\"").append(AdminPageRenderer.esc(t.tenantId)).append("\">")
                    .append(AdminPageRenderer.esc(t.tenantId)).append("</option>");
        }
        return sb.toString();
    }

    private static String dtDd(String label, String value) {
        return "<div><dt class=\"text-xs uppercase tracking-wider text-ink-mute\">"
                + AdminPageRenderer.esc(label)
                + "</dt><dd class=\"break-all font-mono text-xs text-slate-200\">"
                + AdminPageRenderer.esc(value == null ? "" : value) + "</dd></div>";
    }
}
