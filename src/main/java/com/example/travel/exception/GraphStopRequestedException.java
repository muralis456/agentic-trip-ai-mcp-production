package com.example.travel.exception;

/** Internal control-flow exception used to unwind LangGraph after a user Stop.
 *  This is deliberately distinct from provider/LLM failures so cancellation
 *  is never routed through retry/failure handling.
 */
public class GraphStopRequestedException extends RuntimeException {
    public GraphStopRequestedException() {
        super("Graph execution stopped by user");
    }

    public GraphStopRequestedException(Throwable cause) {
        super("Graph execution stopped by user", cause);
    }
}
