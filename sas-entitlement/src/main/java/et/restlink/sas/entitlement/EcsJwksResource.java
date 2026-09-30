/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.entitlement;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * {@code GET /jwks.json} on the entitlement server: the public ES256 key(s) operator
 * tokens are signed with (RFC 7517). A CAMARA server lists this URL as the issuer's
 * {@code jwks_uri} and verifies tokens offline — no call back to the ECS per token.
 */
@Path("/jwks.json")
public class EcsJwksResource {

    @Inject
    EcsTokenKeys keys;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response jwks() {
        return Response.ok(keys.publicJwksJson())
                .header("Cache-Control", "public, max-age=300")
                .build();
    }
}
