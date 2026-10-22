package com.example.ialocal.runtime.remote;

import com.example.ialocal.runtime.remote.IInferenceCallback;
import com.example.ialocal.runtime.remote.IInferenceStateListener;

/**
 * The llama.cpp runtime, which runs in the app's ":ai" process so that Android can stop it for
 * lack of memory without closing the app. Models and requests travel as JSON; failures arrive as
 * IllegalStateException with a message for the user.
 */
interface IInferenceService {
    int pid();

    void setStateListener(IInferenceStateListener listener);

    /**
     * Loads the model and returns when it is ready. A non-empty prefillJson (a PromptPrefill) also
     * processes what the next answer starts from.
     */
    void warmUp(String modelJson, int safeLevel, String prefillJson);

    /** Loads the model and runs a short test answer; returns the answer. Frees it unless keepLoaded. */
    String verify(String modelJson, int safeLevel, boolean keepLoaded);

    /** Starts an answer; its text, end or failure arrive on the callback. */
    void generate(String requestId, String requestJson, IInferenceCallback callback);

    void cancel(String requestId);

    String benchmark(String modelJson, int safeLevel, String profile);

    void unload();

    boolean releaseIfIdle();
}
