package com.example.ialocal.runtime.remote;

/** One answer being generated, in order. */
oneway interface IInferenceCallback {
    /** A step before the first word (a GenerationStage as JSON): loading, reading the prompt… */
    void onStage(String stageJson);

    void onToken(String text);

    void onDone();

    void onError(String message);
}
