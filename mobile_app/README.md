# Local Gemma 4 E4B Transcript

This app runs `google/gemma-4-E4B-it` locally on Android through LiteRT-LM.

The live UI is split into:

- a camera feed from the Ray-Ban Meta glasses
- a live classified caption bar backed by Gemma + SNOMED / ICD-10-CM / AIS tools
- an active conversation transcript sourced from the glasses microphone over Bluetooth SCO

It also bundles three offline terminology resources used during live frame classification:

- `snomed.txt`
- `icd10cm_codes_2026.txt`
- `ais_codes.txt`

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

## Ontology resources

The ontology files are shipped as `res/raw/` resources and copied on first launch to:

```text
<external-files>/ontology/
```

For development, adb-pushed overrides are checked first:

```text
/data/local/tmp/ontology/
```

You can push replacement files without reinstalling the app:

```sh
adb shell mkdir -p /data/local/tmp/ontology/
adb push snomed.txt /data/local/tmp/ontology/snomed.txt
adb push icd10cm_codes_2026.txt /data/local/tmp/ontology/icd10cm_codes_2026.txt
adb push ais_codes.txt /data/local/tmp/ontology/ais_codes.txt
adb push ontology_manifest.json /data/local/tmp/ontology/ontology_manifest.json
```

The app currently expects:

- `snomed.txt`: tab-separated SNOMED CT concept export with `ConceptID`, `Active`, `FSN`, and preferred term columns
- `icd10cm_codes_2026.txt`: ICD-10-CM code plus description text
- `ais_codes.txt`: AIS 1985 code list with section headers and `XXXXX.Y description` rows

Licensing expectations remain the developer's responsibility before redistribution:

- SNOMED CT distribution may require a UMLS or other applicable affiliate/license arrangement.
- ICD-10-CM is broadly available for US use, but verify the exact data source you package.
- AIS content has its own licensing terms and should be reviewed before release.

## Whisper transcription assets

Offline transcription now runs through the vendored `:whisper-lib` module, which
wraps `whisper.cpp` through JNI.

The Whisper model is downloaded on the first transcription attempt to:

```text
<external-files>/whisper/
```

Downloaded file:

- `ggml-tiny.en.bin`

For development, adb-pushed overrides are checked first:

```text
/data/local/tmp/whisper/ggml-tiny.en.bin
```

You can replace the transcription model without reinstalling the app:

```sh
adb shell mkdir -p /data/local/tmp/whisper/
adb push ggml-tiny.en.bin /data/local/tmp/whisper/ggml-tiny.en.bin
```

The transcription path is fully offline at runtime:

- audio is routed from the Ray-Ban microphone to the phone through Bluetooth SCO / HFP
- `AudioRecord` captures 16 kHz mono PCM on the phone
- `UtteranceChunker` emits partial and final speech windows
- `WhisperEngine` normalizes PCM and hands it to whisper.cpp for decoding

If SCO routing is unavailable, the app falls back to the phone microphone and surfaces that state in the transcript UI.

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

## Capture sidecars

The live capture flow saves a JPEG plus a JSON sidecar under:

```text
<external-files>/photos/
```

Files share the same timestamp stem:

- `classified_<timestamp>.jpg`
- `classified_<timestamp>.json`

Current sidecar schema:

```json
{
  "schemaVersion": 1,
  "capturedAt": 1713450000000,
  "statement": "Forehead laceration with active bleeding.",
  "designations": [
    {
      "source": "snomed",
      "primaryId": "312608009",
      "preferredTerm": "Laceration of forehead",
      "detail": "Laceration of forehead (disorder)"
    }
  ]
}
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
