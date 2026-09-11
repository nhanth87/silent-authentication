/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.camara;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
@ApplicationScoped
public class CamaraUnrecognizedPropertyExceptionMapper
        implements ExceptionMapper<UnrecognizedPropertyException> {

    @Override
    public Response toResponse(UnrecognizedPropertyException exception) {
        String property = exception.getPropertyName();
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(CamaraErrorInfo.invalidArgument(
                        "unknown request property: " + (property == null ? "unspecified" : property)))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
