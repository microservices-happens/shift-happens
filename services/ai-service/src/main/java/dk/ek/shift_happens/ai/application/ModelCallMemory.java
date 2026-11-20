package dk.ek.shift_happens.ai.application;

/**
 * Keeps the outcome of the model call between the delivery attempts of one message.
 *
 * <p>A message is tried three times before it is dead-lettered. The model call is the slow part (up to
 * {@code LLM_TIMEOUT}), so a retry caused by something after it, such as the database, reuses the first
 * outcome instead of calling the model again. That bounds one message to a single model call.
 */
public interface ModelCallMemory {

    /** What the model answered: {@code raw} is its response, or null when the call failed ({@code failure}). */
    record ModelCall(String raw, String failure) {
    }

    /** The outcome of an earlier attempt of the same message, or null on the first attempt. */
    ModelCall recall();

    void remember(ModelCall call);

    /** No memory: every call of {@link SuggestionService#handle} may call the model. */
    static ModelCallMemory none() {
        return new ModelCallMemory() {
            @Override
            public ModelCall recall() {
                return null;
            }

            @Override
            public void remember(ModelCall call) {
            }
        };
    }
}
