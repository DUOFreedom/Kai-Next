# Getting Started

## Installation

Kai is Android only in this fork. The iOS, desktop and Web builds of the upstream project are not produced here.

### App Stores

- [**Google Play**](https://play.google.com/store/apps/details?id=com.kainext.duo) (Android)
- [**F-Droid**](https://f-droid.org/en/packages/com.kainext.duo/) (Android)

### Direct Downloads

| Platform | Format | Download |
|----------|--------|----------|
| Android | APK | [GitHub Releases](https://github.com/DUOFreedom/Kai-Next/releases) |

## First Steps

1. Launch Kai — you'll see the chat screen with an animated welcome
2. Start chatting immediately using the **Free** tier (no API key needed)
3. For better models, open **Settings** and add a service (e.g. OpenAI, Gemini, DeepSeek)
4. Enter your API key — Kai validates the connection and loads available models automatically
5. Prefer to stay offline? On Android, add the **Local Model** service and download an on-device model — see [On-Device Inference](features/on-device-inference.md)

## Adding a Service

1. Open Settings
2. Tap **Add Service** and pick a provider
3. Paste your API key
4. Select a model from the dropdown
5. Drag services to reorder — the first one is your primary, the rest are fallbacks

See [Multi-Service](features/multi-service.md) for the full details on providers and fallback behavior.
