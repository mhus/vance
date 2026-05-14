package de.mhus.vance.api.hactar;

/**
 * Task types in a workflow state's {@code type:} field. See the plan
 * §4 for the full inventory; all types share the uniform lifecycle
 * defined in §4.0.
 */
public enum HactarTaskType {
    /** Engine-spawn (Jeltz/Ford/Vogon/Marvin). §4.1 */
    AGENT_TASK,
    /** Shell command via the {@code script-engine}. §4.2 */
    SCRIPT_TASK,
    /** Direct tool invocation via {@code ContextToolsApi}. §4.3 */
    TOOL_TASK,
    /** User-Inbox gate. §4.4 */
    GATE_TASK,
    /** Delay until {@code fireAt}. §4.5 */
    TIMER_TASK,
    /** Pure SpEL condition fan-out. §4.6 */
    CONDITION_TASK,
    /** Blocking sub-workflow spawn. §4.7 */
    WORKFLOW_TASK,
    /** Terminal end state. §4.8 */
    TERMINAL
}
