package com.yuzee.tokenlab.model;

import java.util.List;

/** Port of skillSuggestions.ts's SkillReview -- the result of selectSkillOffers(). */
public class SkillReview {
    private String status; // "ready" | "abstained"
    private List<SkillOffer> offers;
    private String reason;

    public SkillReview() {}
    public SkillReview(String status, List<SkillOffer> offers, String reason) {
        this.status = status;
        this.offers = offers;
        this.reason = reason;
    }

    public static SkillReview noSkills(String reason) {
        return new SkillReview("abstained", java.util.List.of(), reason);
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<SkillOffer> getOffers() { return offers; }
    public void setOffers(List<SkillOffer> offers) { this.offers = offers; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
