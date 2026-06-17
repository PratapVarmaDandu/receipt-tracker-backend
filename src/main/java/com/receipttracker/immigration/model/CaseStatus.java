package com.receipttracker.immigration.model;

import java.util.EnumSet;
import java.util.Set;

public enum CaseStatus {
    PROSPECTIVE,
    DATA_COLLECTION,
    PETITION_FILED,
    RFE_PENDING,
    PETITION_APPROVED,
    DS160_FILED,
    INTERVIEW_SCHEDULED,
    VISA_ISSUED,
    ADMITTED,
    CLOSED,
    DENIED,
    WITHDRAWN;

    // Allowed transitions: key → set of valid next statuses
    public boolean canTransitionTo(CaseStatus next) {
        return switch (this) {
            case PROSPECTIVE         -> Set.of(DATA_COLLECTION, WITHDRAWN).contains(next);
            case DATA_COLLECTION     -> Set.of(PETITION_FILED, WITHDRAWN).contains(next);
            case PETITION_FILED      -> Set.of(RFE_PENDING, PETITION_APPROVED, DENIED, WITHDRAWN).contains(next);
            case RFE_PENDING         -> Set.of(PETITION_APPROVED, DENIED, WITHDRAWN).contains(next);
            case PETITION_APPROVED   -> Set.of(DS160_FILED, INTERVIEW_SCHEDULED, VISA_ISSUED, CLOSED, WITHDRAWN).contains(next);
            case DS160_FILED         -> Set.of(INTERVIEW_SCHEDULED, DENIED, WITHDRAWN).contains(next);
            case INTERVIEW_SCHEDULED -> Set.of(VISA_ISSUED, DENIED, WITHDRAWN).contains(next);
            case VISA_ISSUED         -> Set.of(ADMITTED, CLOSED).contains(next);
            case ADMITTED            -> Set.of(CLOSED).contains(next);
            case CLOSED, DENIED, WITHDRAWN -> false;
        };
    }

    public static final Set<CaseStatus> TERMINAL = EnumSet.of(CLOSED, DENIED, WITHDRAWN);
}
