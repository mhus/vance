package de.mhus.vance.brain.ai;

import java.util.OptionalInt;

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
     * @return the cap now in force for {@code modelLabel}, or empty when the
     *         message carried no number and nothing could be learned. The
     *         caller needs this to tell the user whether the next attempt has
     *         any chance of being different — promising a tightened budget
     *         after learning nothing sends them into an identical failure.
     */
    OptionalInt learn(String modelLabel, String errorText, int requestedTools);
}
