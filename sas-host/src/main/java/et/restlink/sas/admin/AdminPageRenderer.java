/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.admin;

import et.restlink.sas.admin.AdminHttpHandler.HttpReply;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AdminPageRenderer {

    private static final Logger LOG = LogManager.getLogger(AdminPageRenderer.class);
    private static final AtomicBoolean LOGGED_ROOT = new AtomicBoolean();
    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{\\{[A-Z][A-Z0-9_]*\\}\\}");

    @ConfigProperty(name = "sas.admin.ui-dir", defaultValue = "app/html")
    String uiDir;

    public HttpReply pageWith(String name, Map<String, String> vars) throws Exception {
        byte[] raw = readFile(uiRoot(), "admin/" + name);
        if (raw == null) {
            if ("index.html".equals(name)) {
                return HttpReply.html(fallbackDashboard());
            }
            return HttpReply.notFound();
        }
        return HttpReply.html(applyTemplateVars(name, new String(raw, StandardCharsets.UTF_8), vars));
    }

    public HttpReply staticResource(String rest) throws Exception {
        Path root = uiRoot();
        byte[] raw = readFile(root, "admin/static/" + rest);
        if (raw == null) {
            return HttpReply.notFound();
        }
        String ct = rest.endsWith(".css") ? "text/css"
                : rest.endsWith(".js") ? "application/javascript; charset=utf-8"
                : rest.endsWith(".html") ? "text/html; charset=utf-8"
                : rest.endsWith(".png") ? "image/png"
                : "application/octet-stream";
        return HttpReply.bytes(ct, raw);
    }

    static String applyTemplateVars(String templateName, String html, Map<String, String> vars) {
        if (html == null) {
            return "";
        }
        String out = html;
        if (vars != null) {
            for (var entry : vars.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                out = out.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        }
        Matcher leftover = TEMPLATE_TOKEN.matcher(out);
        if (leftover.find()) {
            LOG.warn("[admin] unsubstituted tokens in {} — stripping", templateName);
            out = leftover.replaceAll("");
        }
        return out;
    }

    public static String fallbackDashboard() {
        return "<!DOCTYPE html><html><head><meta charset=utf-8/><title>Restlink SAS</title></head>"
                + "<body><h1>Restlink Silent Auth SAS</h1><ul>"
                + "<li><a href=/admin/ss7>SS7</a></li><li><a href=/admin/http>HTTP</a></li>"
                + "<li><a href=/admin/diameter>Diameter</a></li><li><a href=/admin/cdr>CDR</a></li>"
                + "<li><a href=/admin/tenants>Tenants</a></li><li><a href=/admin/users>Users</a></li>"
                + "</ul></body></html>";
    }

    public static String esc(Object o) {
        if (o == null) {
            return "";
        }
        return o.toString().replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    Path uiRoot() {
        Path root = resolveUiRoot(uiDir);
        if (LOGGED_ROOT.compareAndSet(false, true)) {
            LOG.info("[admin] UI directory: {}", root);
        }
        return root;
    }

    static Path resolveUiRoot(String configured) {
        String cfg = configured == null || configured.isBlank() ? "app/html" : configured.trim();
        Path p = Path.of(cfg);
        if (!p.isAbsolute()) {
            p = Path.of(System.getProperty("user.dir", ".")).resolve(p);
        }
        return p.toAbsolutePath().normalize();
    }

    static byte[] readFile(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.contains("..")) {
            return null;
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            return null;
        }
        return Files.readAllBytes(resolved);
    }
}