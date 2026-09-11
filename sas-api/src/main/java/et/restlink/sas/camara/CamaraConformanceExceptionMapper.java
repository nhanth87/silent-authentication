/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.camara;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
@ApplicationScoped
public class CamaraConformanceExceptionMapper implements ExceptionMapper<CamaraConformanceException> {

    @Override
    public Response toResponse(CamaraConformanceException exception) {
        return Response.status(exception.status())
                .entity(new CamaraErrorInfo(exception.status(), exception.code(),
                        exception.getMessage()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
