/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.camara;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

@Provider
@ApplicationScoped
@Priority(Priorities.HEADER_DECORATOR)
public class CamaraCorrelatorResponseFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) {
        if (!CamaraPaths.isCamaraPath(requestContext.getUriInfo().getPath())) {
            return;
        }
        String value = requestContext.getHeaderString(CamaraHeaders.CORRELATOR);
        if (value == null || value.isBlank() || !CamaraCorrelatorRequestFilter.isValid(value)) {
            responseContext.getHeaders().remove(CamaraHeaders.CORRELATOR);
            return;
        }
        responseContext.getHeaders().putSingle(CamaraHeaders.CORRELATOR, value);
    }
}
