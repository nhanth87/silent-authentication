/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.subscriber;

import et.restlink.sas.subscriber.dto.ProblemDetails;

import java.util.Objects;

/**
 * Either the 200 answer or a problem — never both, never neither. A caller that does not
 * get {@link #ok()} must not approve anything (fail closed).
 */
public record ExposureOutcome<T>(T value, ProblemDetails problem) {

    public ExposureOutcome {
        if ((value == null) == (problem == null)) {
            throw new IllegalArgumentException("exactly one of value / problem");
        }
    }

    public static <T> ExposureOutcome<T> ok(T value) {
        return new ExposureOutcome<>(Objects.requireNonNull(value), null);
    }

    public static <T> ExposureOutcome<T> problem(ProblemDetails problem) {
        return new ExposureOutcome<>(null, Objects.requireNonNull(problem));
    }

    public static <T> ExposureOutcome<T> problem(ExposureCause cause, String detail) {
        return problem(cause.problem(detail));
    }

    public boolean ok() {
        return value != null;
    }

    /** The cause code of a problem, or {@code null} for a 200. */
    public String cause() {
        return problem == null ? null : problem.cause();
    }
}
