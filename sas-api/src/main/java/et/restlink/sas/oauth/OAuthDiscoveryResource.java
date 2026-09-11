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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

import java.util.Map;

@Path("/.well-known")
@Produces(MediaType.APPLICATION_JSON)
public class OAuthDiscoveryResource {

    @Inject
    OAuthMetadataService metadata;

    @GET
    @Path("/openid-configuration")
    public Map<String, Object> openidConfiguration(@Context UriInfo uriInfo) {
        return metadata.metadata(uriInfo);
    }

    @GET
    @Path("/oauth-authorization-server")
    public Map<String, Object> authorizationServerMetadata(@Context UriInfo uriInfo) {
        return metadata.metadata(uriInfo);
    }
}
