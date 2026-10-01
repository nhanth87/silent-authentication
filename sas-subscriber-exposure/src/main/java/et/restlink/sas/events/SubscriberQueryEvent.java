/*
 * Silent Auth SAS — Restlink (Ethiopia).
 * micro-jainslee application. Java 25. R&D only — never production.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package et.restlink.sas.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

import et.restlink.sas.subscriber.ExposureContext;

import java.util.Objects;

/**
 * One subscriber exposure call, routed to {@code SubscriberSbb} (sas-ecs). The REST
 * resource only builds this event and waits; the SBB owns the signalling.
 *
 * <p>{@code activityId} is unique per execution (it is also the RA command id), while
 * {@code answerKey} is the dedup key the coordinator completes — the two differ so a
 * retried call never collides with the RA sessions of the first one.</p>
 */
@EventType(name = "SubscriberQuery", vendor = "et.restlink.sas", version = "1.0")
public final class SubscriberQueryEvent implements SleeEvent {

    /** The four operations of the exposure contract. */
    public enum Operation {
        UE_ID, MONITORING, SIM_CHANGE, REDEEM
    }

    private final String activityId;
    private final String answerKey;
    private final Operation operation;
    private final ExposureContext context;
    private final Object request;
    private final String scsAsId;

    public SubscriberQueryEvent(String activityId, String answerKey, Operation operation,
                                ExposureContext context, Object request, String scsAsId) {
        this.activityId = Objects.requireNonNull(activityId);
        this.answerKey = Objects.requireNonNull(answerKey);
        this.operation = Objects.requireNonNull(operation);
        this.context = Objects.requireNonNull(context);
        this.request = Objects.requireNonNull(request);
        this.scsAsId = scsAsId;
    }

    public String activityId() {
        return activityId;
    }

    public String answerKey() {
        return answerKey;
    }

    public Operation operation() {
        return operation;
    }

    public ExposureContext context() {
        return context;
    }

    /** The validated request DTO of {@link #operation()}. */
    public <T> T request(Class<T> type) {
        return type.cast(request);
    }

    public String scsAsId() {
        return scsAsId;
    }

    @Override
    public String toString() {
        // Never print the request: it carries a number or a token.
        return "SubscriberQueryEvent[" + operation + ", activity=" + activityId + "]";
    }
}
