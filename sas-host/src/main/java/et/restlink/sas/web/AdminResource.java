/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.web;

import et.restlink.sas.admin.AdminHttpHandler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Quarkus REST adapter that forwards {@code /admin*} and {@code /} traffic to
 * the framework-free {@link AdminHttpHandler}. The {@code /verify} resource is
 * a separate literal path and is never intercepted here.
 */
@Path("/")
@ApplicationScoped
public class AdminResource {

    @Inject
    AdminHttpHandler handler;

    @GET
    @Path("/")
    public Response root(@Context HttpHeaders headers, @Context UriInfo uri) {
        return delegate("GET", "/", headerMap(headers), queryMap(uri), "");
    }

    @GET
    @Path("admin")
    public Response adminRootGet(@Context HttpHeaders headers, @Context UriInfo uri) {
        return delegate("GET", "/admin", headerMap(headers), queryMap(uri), "");
    }

    @POST
    @Path("admin")
    public Response adminRootPost(@Context HttpHeaders headers, @Context UriInfo uri, String body) {
        return delegate("POST", "/admin", headerMap(headers), queryMap(uri), body);
    }

    @GET
    @Path("admin/{path:.+}")
    public Response adminGet(@PathParam("path") String path,
                             @Context HttpHeaders headers,
                             @Context UriInfo uri) {
        return delegate("GET", "/admin/" + path, headerMap(headers), queryMap(uri), "");
    }

    @POST
    @Path("admin/{path:.+}")
    public Response adminPost(@PathParam("path") String path,
                              @Context HttpHeaders headers,
                              @Context UriInfo uri,
                              String body) {
        return delegate("POST", "/admin/" + path, headerMap(headers), queryMap(uri), body);
    }

    private Response delegate(String method, String path, Map<String, String> headers,
                              Map<String, String> query, String body) {
        Optional<AdminHttpHandler.HttpReply> reply = handler.tryHandle(method, path, headers, query, body, null);
        if (reply.isEmpty()) {
            return Response.status(404).build();
        }
        AdminHttpHandler.HttpReply r = reply.get();
        Response.ResponseBuilder rb = Response.status(r.status());
        if (r.body() != null) {
            rb.entity(r.body());
        }
        if (r.contentType() != null) {
            rb.type(r.contentType());
        }
        if (r.headers() != null) {
            for (var e : r.headers().entrySet()) {
                if ("Set-Cookie".equalsIgnoreCase(e.getKey())) {
                    for (String cookie : e.getValue().split(AdminHttpHandler.HttpReply.SET_COOKIE_SEP)) {
                        rb.header("Set-Cookie", cookie);
                    }
                } else {
                    rb.header(e.getKey(), e.getValue());
                }
            }
        }
        return rb.build();
    }

    private static Map<String, String> headerMap(HttpHeaders headers) {
        return toMap(headers == null ? null : headers.getRequestHeaders());
    }

    private static Map<String, String> queryMap(UriInfo uri) {
        return toMap(uri == null ? null : uri.getQueryParameters());
    }

    private static Map<String, String> toMap(MultivaluedMap<String, String> mv) {
        Map<String, String> out = new LinkedHashMap<>();
        if (mv == null) {
            return out;
        }
        for (var entry : mv.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                out.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        return out;
    }
}