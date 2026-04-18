# Local Gemma 4 E4B Chat

This app runs `google/gemma-4-E4B-it` locally on Android through LiteRT-LM.

## Model

The app can download the Android-ready model bundle automatically when it is missing.

Model source:

- [`litert-community/gemma-4-E4B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm)

The expected file name is:

- `gemma-4-E4B-it.litertlm`

## Auto-download flow

- On first launch, if the model is missing, the app asks for a Hugging Face access token.
- The token must have access to the gated Gemma repo, and your account must have accepted the Gemma license on Hugging Face.
- The app downloads the model into app-specific external storage with Android `DownloadManager`.
- GPU is still preferred, with CPU fallback if GPU initialization fails.

The downloaded model is stored under the app's external files directory in a `litertlm/` subfolder.

## Manual push for development

An adb-pushed model still works and is checked before the in-app download location:

```text
/data/local/tmp/litertlm/gemma-4-E4B-it.litertlm
```

To push it manually:

```sh
adb shell mkdir -p /data/local/tmp/litertlm/
adb push gemma-4-E4B-it.litertlm /data/local/tmp/litertlm/gemma-4-E4B-it.litertlm
```

## Expected runtime behavior

- Target device: Samsung S26 Ultra with 12 GB RAM
- Preferred backend: GPU
- CPU fallback is automatic if GPU initialization fails

From the LiteRT-LM model card, the S26 Ultra should roughly reach:

- GPU decode: about 22 tokens/sec
- GPU working memory: about 710 MB
- CPU working memory: about 3.2 GB

## Troubleshooting

- If the app keeps asking for a token, make sure the token can access the gated model and that you accepted the Gemma license on Hugging Face.
- If the app shows "Model missing", verify the manual path above or retry the in-app download.
- The first initialization can take several seconds.
- If GPU initialization fails, the app should fall back to CPU and report that in the UI.
