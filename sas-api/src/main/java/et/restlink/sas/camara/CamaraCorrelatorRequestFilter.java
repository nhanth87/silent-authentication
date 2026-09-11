/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.camara;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.regex.Pattern;

@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 100)
public class CamaraCorrelatorRequestFilter implements ContainerRequestFilter {

    private static final Pattern VALID_CORRELATOR =
            Pattern.compile("^[a-zA-Z0-9\\-_:;./<>{}]{0,256}$");

    public static boolean isValid(String value) {
        return value == null || VALID_CORRELATOR.matcher(value).matches();
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!CamaraPaths.isCamaraPath(requestContext.getUriInfo().getPath())) {
            return;
        }
        String value = requestContext.getHeaderString(CamaraHeaders.CORRELATOR);
        if (isValid(value)) {
            return;
        }
        requestContext.abortWith(Response.status(Response.Status.BAD_REQUEST)
                .entity(CamaraErrorInfo.invalidArgument(
                        "x-correlator must match ^[a-zA-Z0-9-_:;./<>{}]{0,256}$"))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
