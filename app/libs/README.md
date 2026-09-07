# llama-android.aar

`llama-android.aar` is intentionally not committed to the project ZIP.

Generate it from the official `ggml-org/llama.cpp` Android binding with:

```bash
./scripts/prepare_llama_android.sh
```

The script pins llama.cpp to `v0.4.0` by default and copies the generated AAR into this directory.
The GitHub Actions workflow runs this step automatically before compiling the APK.
