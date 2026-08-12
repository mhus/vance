package de.mhus.vance.brain.ai;

/**
 * Sink for "this endpoint enforces a smaller {@code tools} array than we
 * thought". Lets the AI layer report the fact without depending on where
 * it is stored — {@code EngineChatFactory} binds the implementation
 * (today {@code ObservedToolLimitRegistry}) the same way it binds the
 * user-notifier and the trace writer.
 */
@FunctionalInterface
public interface ToolLimitLearner {

    /**
     * @param modelLabel     {@code providerInstance:modelName} of the
     *                       rejecting entry ({@code AiChatConfig.fullName()})
     * @param errorText      the provider's message, cause chain included
     * @param requestedTools how many tool schemas the request carried
     */
    void learn(String modelLabel, String errorText, int requestedTools);
}
