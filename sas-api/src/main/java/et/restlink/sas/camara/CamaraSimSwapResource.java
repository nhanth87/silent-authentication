/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.camara;

import et.restlink.sas.simswap.SimSwapCheckRequest;
import et.restlink.sas.simswap.SimSwapDateRequest;
import et.restlink.sas.simswap.SimSwapResource;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Singleton
@Path("/camara/sim-swap/v2")
public class CamaraSimSwapResource {

    private final SimSwapResource delegate;

    @Inject
    public CamaraSimSwapResource(SimSwapResource delegate) {
        this.delegate = delegate;
    }

    @POST
    @Path("/check")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response check(SimSwapCheckRequest body,
                          @HeaderParam("x-correlator") String xCorrelator,
                          @HeaderParam("Authorization") String authorization) {
        return delegate.check(body, xCorrelator, authorization);
    }

    @POST
    @Path("/retrieve-date")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response retrieveDate(SimSwapDateRequest body,
                                 @HeaderParam("x-correlator") String xCorrelator,
                                 @HeaderParam("Authorization") String authorization) {
        return delegate.retrieveDate(body, xCorrelator, authorization);
    }
}
