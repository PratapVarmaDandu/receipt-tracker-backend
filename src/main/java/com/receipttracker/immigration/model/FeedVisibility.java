package com.receipttracker.immigration.model;

/** Controls which roles can see an audit/feed event. */
public enum FeedVisibility {
    ALL,
    ATTORNEY_ONLY,
    BENEFICIARY_ONLY
}
