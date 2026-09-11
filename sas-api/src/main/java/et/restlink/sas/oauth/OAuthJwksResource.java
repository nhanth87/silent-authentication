/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.oauth;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

@Path("/jwks.json")
@Produces(MediaType.APPLICATION_JSON)
public class OAuthJwksResource {

    @Inject
    OAuthMetadataService metadata;

    @GET
    public Map<String, Object> jwks() {
        return metadata.jwks();
    }
}
