package com.yuzee.tokenlab.model;

/**
 * One row of config/microtools.json (ported verbatim from src/routing/microtools.json).
 * Field names match the JSON keys directly so no Jackson annotations are needed.
 */
public class MicroTool {
    private String id;
    private String name;
    private String domain;
    private String purpose;
    private String use_when;
    private String trigger_examples;
    private String mini_prompt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getUse_when() { return use_when; }
    public void setUse_when(String use_when) { this.use_when = use_when; }
    public String getTrigger_examples() { return trigger_examples; }
    public void setTrigger_examples(String trigger_examples) { this.trigger_examples = trigger_examples; }
    public String getMini_prompt() { return mini_prompt; }
    public void setMini_prompt(String mini_prompt) { this.mini_prompt = mini_prompt; }
}
