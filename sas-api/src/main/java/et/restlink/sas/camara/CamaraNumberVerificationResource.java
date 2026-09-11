/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.camara;

import et.restlink.sas.api.VerifyResource;
import et.restlink.sas.api.dto.VerifyRequestDto;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Singleton
@Path("/camara/number-verification/v2")
public class CamaraNumberVerificationResource {

    private final VerifyResource delegate;

    @Inject
    public CamaraNumberVerificationResource(VerifyResource delegate) {
        this.delegate = delegate;
    }

    @POST
    @Path("/verify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response verify(VerifyRequestDto body,
                           @HeaderParam("x-correlator") String xCorrelator,
                           @HeaderParam("Authorization") String authorization,
                           @HeaderParam("X-Sas-Amr") String amr,
                           @HeaderParam("X-Sas-Src-Ip") String srcIpHeader,
                           @HeaderParam("X-Sas-Src-Port") String srcPortHeader,
                           @HeaderParam("X-Sas-Access-Tech") String accessTechHeader,
                           @HeaderParam("X-Sas-Operator-Token") String operatorTokenHeader,
                           @HeaderParam("X-Sas-Risk-Class") String riskClassHeader,
                           @HeaderParam("X-Sas-Assurance-Detail") String assuranceDetailHeader) {
        return delegate.verifyV2(body, xCorrelator, authorization, amr, srcIpHeader,
                srcPortHeader, accessTechHeader, operatorTokenHeader, riskClassHeader,
                assuranceDetailHeader);
    }

    @GET
    @Path("/device-phone-number")
    @Produces(MediaType.APPLICATION_JSON)
    public Response devicePhoneNumber(@HeaderParam("x-correlator") String xCorrelator,
                                      @HeaderParam("Authorization") String authorization,
                                      @HeaderParam("X-Sas-Amr") String amr,
                                      @HeaderParam("X-Sas-Src-Ip") String srcIpHeader,
                                      @HeaderParam("X-Sas-Src-Port") String srcPortHeader,
                                      @HeaderParam("X-Sas-Access-Tech") String accessTechHeader) {
        return delegate.devicePhoneNumber(xCorrelator, authorization, amr, srcIpHeader,
                srcPortHeader, accessTechHeader);
    }
}
