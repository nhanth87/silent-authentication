/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.camara;

import et.restlink.sas.otpsms.OtpSmsResource;
import et.restlink.sas.otpsms.SendCodeRequest;
import et.restlink.sas.otpsms.ValidateCodeRequest;

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
@Path("/camara/one-time-password-sms/v1")
public class CamaraOtpSmsResource {

    private final OtpSmsResource delegate;

    @Inject
    public CamaraOtpSmsResource(OtpSmsResource delegate) {
        this.delegate = delegate;
    }

    @POST
    @Path("/send-code")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response sendCode(SendCodeRequest body,
                             @HeaderParam("x-correlator") String xCorrelator,
                             @HeaderParam("Authorization") String authorization) {
        return delegate.sendCode(body, xCorrelator, authorization);
    }

    @POST
    @Path("/validate-code")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response validateCode(ValidateCodeRequest body,
                                 @HeaderParam("x-correlator") String xCorrelator,
                                 @HeaderParam("Authorization") String authorization) {
        return delegate.validateCode(body, xCorrelator, authorization);
    }
}
