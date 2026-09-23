package com.yuzee.tokenlab.model.warehouse;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** The user's saved role/skill selections from an exploration workspace. Ported from
 *  yuzee-ai-token-lab/src/warehouse/types.ts (ExplorationChoice) and choices.ts (explorationChoice). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ExplorationChoice {
    private List<String> roleIds = new ArrayList<>();
    private List<SkillState> skills = new ArrayList<>();

    public List<String> getRoleIds() { return roleIds; }
    public void setRoleIds(List<String> roleIds) { this.roleIds = roleIds; }
    public List<SkillState> getSkills() { return skills; }
    public void setSkills(List<SkillState> skills) { this.skills = skills; }


    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class SkillState {
        private String id;
        private String name;
        private String state; // HAVE | LEARN | UNSURE
        public SkillState() {}
        public SkillState(String id, String name, String state) { this.id = id; this.name = name; this.state = state; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
    }
}
