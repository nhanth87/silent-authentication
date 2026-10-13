/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.camara;

import et.restlink.sas.qrsession.QrSessionRequests;
import et.restlink.sas.qrsession.QrSessionResource;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The {@code /camara}-prefixed alias of the QR hand-off.
 *
 * <p>CAMARA clients are configured with an {@code apiRoot} and every published
 * API answers beneath it. {@code /verify} is served both bare and under
 * {@code /camara/number-verification/v2}; this alias gives the hand-off the same
 * two spellings, so a client that builds its URLs from {@code apiRoot} and a
 * client that hard-codes the operator's root agree.</p>
 *
 * <p>It is a pure delegate — no behaviour, no state, no second scoring path. The
 * alias is registered here rather than by a wildcard so that the contract stays
 * readable: {@link QrSessionResource} is the only implementation.</p>
 */
@Singleton
@Path("/camara/qr-session/v1")
public class CamaraQrSessionResource {

    private final QrSessionResource delegate;

    @Inject
    public CamaraQrSessionResource(QrSessionResource delegate) {
        this.delegate = delegate;
    }

    @POST
    @Path("")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(QrSessionRequests.CreateRequest body,
                           @HeaderParam("x-correlator") String xCorrelator,
                           @HeaderParam("Authorization") String authorization) {
        return delegate.create(body, xCorrelator, authorization);
    }

    @POST
    @Path("/{id}/claim")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response claim(@PathParam("id") String sessionId,
                          QrSessionRequests.ClaimRequest body,
                          @HeaderParam("x-correlator") String xCorrelator,
                          @HeaderParam("Authorization") String authorization,
                          @HeaderParam("X-Sas-Src-Ip") String srcIpHeader,
                          @HeaderParam("X-Sas-Src-Port") String srcPortHeader,
                          @HeaderParam("X-Sas-Risk-Class") String riskClassHeader) {
        return delegate.claim(sessionId, body, xCorrelator, authorization,
                srcIpHeader, srcPortHeader, riskClassHeader);
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@PathParam("id") String sessionId,
                           @HeaderParam("x-correlator") String xCorrelator) {
        return delegate.status(sessionId, xCorrelator);
    }
}