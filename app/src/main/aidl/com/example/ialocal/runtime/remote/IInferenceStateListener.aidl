package com.example.ialocal.runtime.remote;

/** What the runtime is doing: a RuntimeStatus name and the model it concerns. */
oneway interface IInferenceStateListener {
    void onState(String status, String modelId, String modelName, String error);
}
