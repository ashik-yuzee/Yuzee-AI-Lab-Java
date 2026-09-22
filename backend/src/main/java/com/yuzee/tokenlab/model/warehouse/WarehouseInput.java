package com.yuzee.tokenlab.model.warehouse;

import java.util.ArrayList;
import java.util.List;

/** Free-text input to {@code WarehouseService.retrieve()}. Ported from
 *  yuzee-ai-token-lab/src/warehouse/types.ts (WarehouseInput). */
public class WarehouseInput {
    private String message;
    private String context = "";
    private List<String> selectedCourseIds = new ArrayList<>();
    private boolean force;

    public WarehouseInput() {}
    public WarehouseInput(String message) { this.message = message; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
    public List<String> getSelectedCourseIds() { return selectedCourseIds; }
    public void setSelectedCourseIds(List<String> selectedCourseIds) { this.selectedCourseIds = selectedCourseIds; }
    public boolean isForce() { return force; }
    public void setForce(boolean force) { this.force = force; }
}
