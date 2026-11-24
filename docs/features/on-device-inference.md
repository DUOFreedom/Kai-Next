# On-Device Inference (LiteRT)

**Last verified:** 2026-11-18

Kai can run AI models directly on the user's device using Google's LiteRT LM SDK. This enables fully offline, private inference with no API key, no internet connection, and no cost. In this fork it is available on **Android** only — the iOS, desktop and web targets and their inference bridges are removed.

## How It Works

Models are downloaded from HuggingFace's litert-community and stored locally on the device. When the user sends a message, the model runs entirely on-device using GPU acceleration (with CPU fallback). The engine initializes on first use (~10 seconds) and stays loaded for 5 minutes of inactivity before automatically releasing memory.

### Download integrity

Every catalog model is pinned to an **immutable HuggingFace revision** rather than a branch, so the bytes served for a given app version never change. Each catalog entry also records the model file's expected SHA-256 and its exact size.

The digest is computed while the download streams to disk, so verification costs no extra read of a file that can be several gigabytes. A download is accepted only when both the length and the digest match; otherwise the partial file is discarded and the failure is reported inline in Settings. The verified digest is recorded in a marker file stored next to the model, written before the model becomes visible under its final name — a model is never present on disk without its marker.

Verification also runs at load time. Before a model is handed to the inference engine, its marker is compared against the pinned digest; when the marker is missing — which is the case for models downloaded by app versions that predate this check — the file is hashed once and the result recorded, making every later load a string comparison. A model whose bytes do not match is refused rather than loaded, and the user is told to delete it and download it again. Nothing is deleted automatically at load time: a rejected file stays on disk for the user to keep, replace, or remove from Settings. Because the observed digest is recorded either way, a file already known not to match is refused immediately instead of being re-read on every attempt.

**Imported models are exempt from verification.** An import whose file name matches a catalog model takes over that catalog slot, so it would otherwise be measured against a digest it was never meant to match — a user's own copy of a model, or one from a different upstream revision, would be rejected. Such an import records that the file is user-supplied, and the load-time check skips it. Imports that land under their own name carry no digest at all and are likewise never checked. Only files Kai itself downloaded are held to the pinned digest.

Bumping a catalog model means updating its pinned revision, digest, and size together. Pin policy, last HuggingFace check, and the bump playbook live in the knowledge bundle under `docs/knowledge/litert/`. Runtime pins stay in the catalog in code; both are checked together with the `update-litert-models` skill. A unit test fails the build if any entry reverts to a mutable branch URL. A newer `main` on HuggingFace is not applied until product asks to bump.

## Available Models

| Model | Size | GPU Memory | Default Context | Max Context | Tool calling |
|-------|------|-----------|-----------------|-------------|--------------|
| Gemma 4 E2B IT | 2.59 GB | 676 MB | 4K tokens | 32K tokens | ✅ reliable |
| Gemma 4 E4B IT | 3.66 GB | 710 MB | 4K tokens | 32K tokens | ✅ reliable |
| Gemma 4 12B IT | 6.88 GB | 4000 MB | 8K tokens | 32K tokens | ✅ reliable |
| LFM2.5 1.2B Instruct | 736 MB | 300 MB | 4K tokens | 4K tokens (fixed) | ✅ tool template in the bundle |
| Qwen3 0.6B | 614 MB (~586 MiB) | 300 MB | 4K tokens | 32K tokens | ⚠️ chat-only in practice |

Models are `.litertlm` files from the [litert-community](https://huggingface.co/litert-community) organization on HuggingFace. Sizes are the exact byte counts recorded in the catalog, which are checked against the downloaded file.

**Gemma 4 12B** is pinned to the revision published in September 2026, which adds vision and audio modalities and Multi-Token Prediction for speculative decoding. That build requires LiteRT-LM 0.17 or newer; this app ships 0.18.1, so the requirement is met.

**LFM2.5 1.2B Instruct** is the smallest catalog model that carries a real tool-calling chat template, so it is the on-device pick when tools matter and a Gemma won't fit. Two properties of the file shape its entry: the catalog takes the `_int4_gpu` build because it is the only one that lowers fully for the GPU delegate (the plain `_int4` leaves ops behind and then fails engine creation) while still running on CPU, which matters because initialization tries GPU first and falls back; and its export tops out at 4K tokens, so the context size is fixed rather than adjustable — the settings card shows the size without a slider.

## Tool support

The application uses **litert-lm's native function calling**: each exposed Kai tool is wrapped in an adapter, registered on the conversation, and the engine drives the tool loop internally. The model uses its trained tool format, and the engine returns the final assistant text after all tool round-trips complete. Automatic tool calling is switched on only when the conversation actually has tools. Tools are available **at any context size** — there's no threshold gating.

Two filters decide what the model actually receives.

**The model file decides whether it gets tools at all.** Before building the tool list, Kai asks the `.litertlm` bundle what it declares about itself — a metadata read that needs no engine and no loaded weights. A bundle that declares no function-calling support carries no tool section in its chat template, and such a model does not politely ignore tools it was handed: it answers *instead* of calling them, inventing whatever the tool would have returned. Those models are given no tools and no tool guidance in the system prompt, which makes them plain chat models — which is what they are. A bundle that cannot answer (older metadata) reports *unknown*, and unknown is treated as before: the allowlist is offered.

**The allowlist then narrows what a tool-capable model sees**, because small Gemma models (2-4B params) struggle to emit valid function-call syntax for tools with many parameters or complex value types, and litert-lm's strict ANTLR parser crashes the call when the syntax is malformed.

The on-device tool allowlist currently exposes: `get_local_time`, `get_location_from_ip`, `web_search`, `open_url`, `memory_store`, `memory_forget`, `memory_reinforce`, and `execute_shell_command` (when the user has enabled the shell tool in Settings). Email tools, task scheduling (`schedule_task` / `list_tasks` / `cancel_task`), MCP server tools, structured `memory_learn`, heartbeat-config tools, and `promote_learning` are excluded — they require a remote model.

**Qwen3 0.6B caveat:** at 0.6 B params it rarely emits valid function-call syntax — it tends to hallucinate answers (e.g. a fictional time) instead of invoking `get_local_time`. Treat Qwen3 as a chat-only model in practice; pick Gemma 4 E2B/E4B, or LFM2.5 1.2B where those are too large, for anything that relies on tools. Whether the capability gate above withholds tools from Qwen3 automatically depends on what its bundle declares; the caveat stands either way.

The system prompt for on-device runs is composed from its own local-chat variant — it contains only the sections a small Gemma can handle (soul + basic memory guidance + runtime Context block). Memory categories, scheduled tasks, Structured Learning guidance, and kai-ui sections are never composed in.

Interactive UI mode is **not supported** on-device: the kai-ui component schema is too large and too structurally complex for 2-4B Gemma models to reliably produce valid kai-ui JSON. The "Start interactive mode" button in the chat empty-state is hidden when the primary service is on-device, and on-device services are filtered out of the interactive-mode service switcher (which only lists models capable of agentic flows) so a user already in Interactive Mode can't switch to them. Users who need interactive UI should switch to a remote service.

See [system-prompts.md](system-prompts.md) for the full contract.

## Sampling

Each conversation is configured with the sampling defaults the model's own bundle declares — top-k, top-p and temperature read from the same capability metadata as the tool declaration. Models converted from different upstream families want different values, so one hardcoded triple suits none of them exactly. A bundle that declares nothing reports zeroes, which mean *no opinion* rather than *decode greedily*; those fall back to Kai's previous fixed values (top-k 40, top-p 0.95, temperature 0.8).

## Tool-call failures

If the engine throws (e.g. the model does emit malformed tool-call syntax that the ANTLR parser rejects), the application catches the failure, logs it, and retries the call **once** with no tools — the user gets a plain-chat answer instead of a hard error.

## Other limitations

- **No image input** -- the on-device engine only accepts text messages. Some catalog bundles (Gemma 4 12B since its September 2026 revision) declare vision and audio modalities, and the capability read surfaces that, but nothing consumes it yet
- **No dynamic UI** -- kai-ui prompts are skipped for on-device runs (the schema is too large for the native template parser)
- **Requires a 64-bit device** -- the LiteRT-LM AAR only ships `arm64-v8a` and `x86_64` native libraries. When the device reports no 64-bit ABI the app creates no local engine, and the LiteRT service card is hidden; the app still works with remote services.

## Model Management

Users manage models through the LiteRT service card in Settings:

- **Download** -- each model card shows a download button with size info; disk space is validated before starting
- **Import** -- a file picker accepts any `.litertlm` already on the device (e.g. from Downloads, Files, or another app that can export the file). The file is **copied** into Kai's private model storage (streaming — never loaded fully into memory). If the file name matches a catalog model, it fills that catalog slot and is marked as user-supplied so it is exempt from the integrity check; otherwise it appears under an **Imported** section with synthetic context/performance defaults
- **Select** -- radio button appears after download/import to set the active model; a successful import auto-selects the new model
- **Delete** -- trash icon removes the downloaded or imported model file
- **Cancel** -- active downloads and imports can be cancelled
- **Error display** -- download and import failures (network, disk space, incomplete, failed integrity check, invalid extension) are shown inline in the settings UI. A model refused at load time reports a separate integrity error in the chat area, pointing the user at Delete and re-download
- **Context size slider** -- each model has a slider to adjust context size (starting at the model's default up to its maximum, in 1K steps); available before download so users can preview performance impact. Gemma 4 12B defaults to 8K (higher than the 4K default used for the smaller E2B/E4B models). Imported custom models default to 4K (max 32K). A model whose maximum equals its default -- LFM2.5, whose export tops out at 4K -- shows the size as a label with no slider, since there is nothing to drag
- **Performance indicator** -- each model shows a Good/OK/Poor label based on total device RAM vs estimated resident memory at the selected context size. The estimate sums the model file size (proxy for resident weights after mmap/PLE), a per-model baseline for GPU/KV working memory, and a per-token KV cache cost that scales with context. Thresholds: Good >= 2.5x, OK >= 1.85x, Poor < 1.85x of total device RAM -- the extra headroom over 1x accounts for OS reservation and GPU-driver overhead. Custom imports use a size-based heuristic for the GPU baseline
- **Free space** -- available device storage is shown below the model list

Downloads run in a foreground service with a notification so they continue when the app is backgrounded. The service is only started once the HTTP connection is established, so a pre-connection failure (e.g. offline) surfaces as an inline error without leaving a promised-but-unfulfilled foreground service. Import always copies into app storage (same pattern as Gallery / PocketPal); Kai cannot silently scan other apps' private model directories.

When the last LiteRT service instance is removed, all downloaded and imported models are automatically deleted.

## Engine Lifecycle

1. **Lazy initialization** -- the engine loads only when the first message is sent
2. **GPU-first** -- attempts the GPU backend, falls back to CPU if unavailable
3. **Memory check** -- refuses to load when available memory is below a fixed **512 MB** headroom floor (not model-size + headroom). Before measuring, any previously loaded engine is released and the GPU driver is given a beat to reclaim its buffers, so switching between models does not spuriously fail the check
4. **Persistent across messages** -- stays loaded for the duration of the conversation
5. **Inference timeout** -- individual inference calls are capped at 2 minutes
6. **Auto-release** -- released after 5 minutes of inactivity to free memory (always re-armed, even on errors)
7. **Status indicator** -- the chat shows "Initializing {model name}" with a pulsing dot during engine load
8. **Stop during load** -- the native model load cannot be interrupted; pressing stop cancels the message, and the load finishes in the background without marking the engine as failed. A follow-up message waits for the in-flight load instead of starting a second one

## Platform Notes

- **Model storage** -- `context.filesDir/litert_models`, with catalog models under `{id}/` (digest marker alongside) and imports under `imports/`
- **Memory and disk** -- available memory comes from `ActivityManager.getMemoryInfo()`; free space from `StatFs`
- **Downloads** -- foreground service with a progress notification
- **Runtime version** -- litert-lm 0.18.1 from Google Maven

## Fallback Behavior

- LiteRT instances participate in the normal fallback chain
- When no local engine exists (a device without a 64-bit ABI), the LiteRT service is filtered out of the settings service list entirely, so it can't be selected
- On-device models are only ever tried as the primary service, never as a fallback target — falling into one would silently start a heavy model load the user didn't ask for
- Background tool runs (heartbeat and scheduled tasks) use an explicitly chosen instance when there is one, otherwise prefer remote services and fall back to on-device only when no remote is configured. The on-device fallback works at any context size, since the simple-tool allowlist has no schema-overhead penalty.

## Key Files

| File | Purpose |
|------|---------|
| `composeApp/src/commonMain/.../data/Service.kt` | `Service.LiteRT` definition with `isOnDevice = true` |
| `composeApp/src/commonMain/.../inference/LocalInferenceEngine.kt` | Platform-agnostic interface for on-device inference, including the declared-capability probe and its sampler-defaults helper |
| `composeApp/src/commonMain/.../inference/LocalModelCatalog.kt` | Bundled model list (pinned revisions, expected digests, sizes, GPU baselines, context defaults) and digest-comparison helpers |
| `docs/knowledge/litert/` | OKF bundle: pin policy, attested commit/digest/size snapshot, refresh playbook |
| `composeApp/src/commonMain/.../inference/LocalModelImport.kt` | Import path helpers, custom model id/filename sanitization, synthetic metadata |
| `composeApp/src/commonMain/.../inference/InferencePlatform.kt` | `expect` declarations for platform-specific operations |
| `composeApp/src/commonMain/.../inference/LocalInferenceEngineProvider.kt` | `expect` factory, returns `null` on a device with no 64-bit ABI |
| `composeApp/src/jvmShared/.../inference/LiteRTInferenceEngine.kt` | The engine implementation wrapping the LiteRT LM SDK, behind the Android actual |
| `composeApp/src/androidMain/.../inference/InferencePlatform.android.kt` | Android platform implementations (storage, memory, disk space, download notification) |
| `composeApp/src/androidMain/.../inference/LocalInferenceEngineProvider.android.kt` | Android factory — creates the engine only when a 64-bit ABI is present |
| `composeApp/src/androidMain/.../inference/ModelDownloadService.kt` | Android foreground service for background downloads |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Inference dispatch, engine initialization status, local tool allowlist, and the declared-capability gate that withholds tools from models with no tool template |
| `composeApp/src/commonMain/.../network/NetworkExceptions.kt` | Maps inference failures, including a failed integrity check, to user-facing errors |
| `composeApp/src/commonMain/.../ui/settings/ServicesSettings.kt` | LiteRT service card: model list, download/import controls, progress and error display |
| `composeApp/src/commonMain/.../ui/settings/SettingsScreen.kt` | Hosts service settings including LiteRT model management |
