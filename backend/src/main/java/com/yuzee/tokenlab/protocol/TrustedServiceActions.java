package com.yuzee.tokenlab.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Port of orchestration/serviceActions.ts TRUSTED_SERVICE_ACTIONS.
 * Authoritative executable-action bindings. Product catalogue entries alone grant no capability.
 * All entries are simulation/reference only until connected to a real live service backend
 * (isConnectedInLab = false on every entry, matching the TS source).
 */
public final class TrustedServiceActions {

    public static class TrustedServiceAction {
        public final String actionId;
        public final String title;
        public final String description;
        public final String category;
        public final boolean requiresConfirmation;
        public final boolean enabled;
        public final boolean isConnectedInLab;

        TrustedServiceAction(String actionId, String title, String description, String category,
                              boolean requiresConfirmation, boolean enabled, boolean isConnectedInLab) {
            this.actionId = actionId;
            this.title = title;
            this.description = description;
            this.category = category;
            this.requiresConfirmation = requiresConfirmation;
            this.enabled = enabled;
            this.isConnectedInLab = isConnectedInLab;
        }
    }

    public static final Map<String, TrustedServiceAction> TRUSTED_SERVICE_ACTIONS = new LinkedHashMap<>();

    static {
        TRUSTED_SERVICE_ACTIONS.put("rmo_explore_courses", new TrustedServiceAction(
            "rmo_explore_courses",
            "Explore Certified Courses",
            "Browse accredited Australian university and VET pathway courses matching your career goal.",
            "RMO",
            false,
            true,
            false
        ));
        TRUSTED_SERVICE_ACTIONS.put("rmo_apply_job", new TrustedServiceAction(
            "rmo_apply_job",
            "Apply for Verified Job Role",
            "Submit an application directly to verified industry employer partners.",
            "RMO",
            true,
            true,
            false
        ));
        TRUSTED_SERVICE_ACTIONS.put("rmo_book_counsellor", new TrustedServiceAction(
            "rmo_book_counsellor",
            "Book 1-on-1 Senior Counsellor Session",
            "Connect with a certified human education and career advisor.",
            "ADVISORY",
            true,
            true,
            false
        ));
        TRUSTED_SERVICE_ACTIONS.put("direct_admission_start", new TrustedServiceAction(
            "direct_admission_start",
            "Initiate Direct Admission",
            "Start structured intake and document verification for priority admission.",
            "DIRECT_APPLICATION",
            true,
            true,
            false
        ));
    }

    public static boolean isTrusted(String actionId) {
        return actionId != null && TRUSTED_SERVICE_ACTIONS.containsKey(actionId);
    }

    private TrustedServiceActions() {
    }
}
