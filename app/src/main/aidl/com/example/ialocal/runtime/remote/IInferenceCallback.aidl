package com.example.ialocal.runtime.remote;

/** One answer being generated, in order. */
oneway interface IInferenceCallback {
    void onToken(String text);

    void onDone();

    void onError(String message);
}
