# iKitty 🐱

> A backend-free Android AI cat companion that talks straight to any OpenAI-compatible API.
>
> [简体中文](README.md) · [Detailed design document](docs/DOC_EN.md) · [Character asset spec](design/cat_v1/README.md)

iKitty is a minimal Jetpack Compose chat app. It assembles a system prompt from the cat persona,
structured long-term memory, and a token-budgeted context window, then sends it to any
OpenAI-compatible endpoint. Chat history, images, memory, and settings stay on the device. Apart from the
model service you configure and the optional IP geolocation, nothing goes through a third-party server.

- App name: **iKitty** · Version: **0.2.1** · Package: `com.example.aicat`
- Repository: <https://github.com/Lixuannan/iKitty>

---

## Features

- **Any OpenAI-compatible service**: seventeen built-in providers — Zhipu GLM, Z.AI, DeepSeek, OpenAI,
  Anthropic, Google Gemini, xAI, DashScope (Qwen), Moonshot/Kimi, MiniMax, ByteDance Doubao, Tencent
  Hunyuan, Baidu ERNIE, Mistral, SiliconFlow, OpenRouter, and local Ollama — plus a custom Base URL.
- **Parameters driven by model capability**: each provider and model exposes a different set of tunable
  parameters with different ranges. The settings screen renders only the controls the model actually
  supports, and the request body sends only the fields it accepts (see [Model capability table](#model-capability-table)).
- **A real test connection**: sends a very short request with exactly the parameters real chat uses and
  reports latency, endpoint, the parameters actually sent, the ones automatically skipped, and token usage.
- **Multimodal image input**: the "+" button picks from the gallery or opens the system camera, with multiple
  images per message, text plus images together, and per-image delete before sending. Images go through the
  standard OpenAI-compatible `image_url` structure, bound to no single model or provider.
- **Streaming replies**: the reply appears incrementally; a provider that ignores `stream` degrades to a
  non-streaming response automatically with the same result.
- **Cat persona**: name, traits (multi-select), speech style, cat flavor, and free-form notes, assembled
  into the system prompt and persisted.
- **7 moods and 6 one-shot animations**: idle, listening, thinking, happy, sad, excited, sleepy, with blink,
  look-around, tail-wag, bounce, shake, and yawn. The model can select them via JSON, or answer as plain text.
- **Local chat history**: append-only JSONL that survives restarts; reading the last N entries does not
  slow down as history grows.
- **Structured long-term memory**: periodically distills durable facts into category + key + value
  entries that are injected into the system prompt. Additive by default, and fully viewable and editable.
- **Token-budgeted context**: assembled newest-first within the model's context window, never splitting a
  question from its answer, automatically omitting the oldest turns in long conversations.
- **IP city geolocation**: lets the cat know roughly which city you are in — no permissions, no dialogs,
  and a settings toggle to turn it off.
- **In-app updates**: the settings screen checks [GitHub releases](https://github.com/Lixuannan/iKitty/releases)
  for a newer version, downloads it, and hands it to the system installer — chat history and memory survive.
- **No backend required.**

## Current shape: chat only

The chat screen currently shows only the header, message bubbles, and the input bar; the small cat avatar
next to each reply is still there. The full Canvas cat component in `CatView.kt`, including mood and
animation parsing, is intact. Restoring it is a matter of dropping `CatView(mood, animation)` back below
the header in `CatChatScreen` — the spot is marked with a comment.

For the layered character assets (PNG layers, pivots, driving suggestions), see
[`design/cat_v1/README.md`](design/cat_v1/README.md).

## Quick start

### Requirements

| Item | Requirement |
| --- | --- |
| Android | 8.0 or newer (minSdk 26), targetSdk 35 |
| Android Studio | Ladybug or newer (must support AGP 8.7.3 / Kotlin 2.0.21) |
| JDK | 17 or newer (source and bytecode target Java 17) |
| Device | Emulator or physical device; a physical device must be able to reach the model endpoint you enter |

### Build and run

```bash
git clone https://github.com/Lixuannan/iKitty.git
cd iKitty

./gradlew assembleDebug        # produces app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # installs to a connected device / emulator
./gradlew testDebugUnitTest    # runs unit tests (plain JVM, no device needed)
```

You can also open the repository root directly in Android Studio and run after the first Gradle sync.

### First-time setup

1. Open the app and tap the gear icon in the top right.
2. Pick a provider (or enter a custom Base URL), fill in the API key, and choose or type a model name.
3. Tap **Test connection** to verify the whole path works.
4. Tap **Save** and return to the chat screen.

## Configuring a model service

The settings screen is ordered top to bottom: cat persona, model service, connection, generation
parameters, location, save.

- **Base URL** is an OpenAI-compatible service address such as `https://example.com/v1`. The app calls
  `POST {Base URL}/chat/completions`; the model list comes from `GET {Base URL}/models`. When a provider
  does not implement that endpoint, the app falls back to its built-in presets — that is not an error.
- **API key** is sent as `Authorization: Bearer <key>`. A blank key sends no authorization header at all,
  which is what lets keyless local services such as Ollama work.
- **Model name** can be picked from the provider's `/models` list or typed in by hand. Hand-typed models
  fall through to name-based heuristics (see [Model capability table](#model-capability-table)).
- **Local / LAN services** (Ollama, LM Studio, vLLM, …) commonly use `http://`; cleartext traffic is
  allowed. On an Android emulator, reach the host machine at `10.0.2.2`; on a physical device, replace it
  with your computer's LAN IP.

> ⚠️ Cleartext traffic is currently permitted for every domain (`network_security_config.xml`
> `base-config`) so local model services work. To tighten this, switch to per-domain `domain-config` entries.

## Cat persona

The **cat persona** section at the top of the settings screen determines the system prompt. Every field
is persisted:

| Setting | Effect |
| --- | --- |
| Name | Used in the header, the greeting, and the prompt; blank falls back to「猫猫」 |
| Traits | Multi-select (up to 3); each maps to one sentence in the prompt |
| Speech style | Single choice: casual, concise, sweet, literary, energetic |
| Cat flavor | Single choice: like a friend / occasional meow / strong cat flavor |
| Notes | Free text appended to the prompt for nicknames, backstory, or taboos |

Assembly lives entirely in `CatPersona.systemPrompt()`; adding a setting touches only that file. The model
capability table (`ModelCatalog`) is independent of it.

## Image input

The "+" on the left of the input bar offers two entries: **choose from gallery** (the system photo picker,
up to 9 at once) and **take photo** (the system camera). Chosen images appear above the input field, each
with its own delete button, so you can send text only, images only, or both together.

Images are first copied into the app-private directory, downscaled, and re-encoded as JPEG before being sent.
The request body uses the OpenAI-compatible multimodal structure (an `image_url` + data URL inside the
`content` array), so it is bound to no particular model or API; whether images are actually understood
depends on the model you choose.

Images count against the context token budget (each at a deliberately high fixed estimate) and are re-sent
alongside text history, so the model still "remembers" the pictures in later turns. A missing image file is
skipped automatically and never breaks text chat.

The system prompt also asks the model to answer with JSON when appropriate, which is what drives mood and
animation:

```json
{"reply": "what to say", "emotion": "neutral|happy|sad|excited|sleepy", "animation": "none|blink|look_around|tail_wag|bounce|shake|yawn"}
```

The parser accepts plain JSON, JSON wrapped in a ``` fence, or plain text. Anything it cannot parse is
treated as an ordinary message; it never throws.

## Chat history and context

Chat history lives in `filesDir/chat/chat_log.jsonl`: one JSON object per line, appended.
A file rather than DataStore or a database, because appending one message costs a write independent of
history length and a crash damages at most the final line. The cost is weak querying, so the store exposes
only the two reads chat actually needs — the last N entries (UI) and everything after a sequence number
(memory extraction). Reading the tail walks backwards through the file in blocks, so tens of thousands of
messages do not slow it down.

Each message stores a `seq` and a timestamp. Ordering uses `seq`, not wall-clock time, because a user
changing the system clock or an NTP correction can move timestamps backwards.

**Context is assembled within a token budget, not truncated by message count** (`ContextAssembler`):

- The window size comes from `ModelCatalog`'s `contextWindow`. Models whose names embed `8k` / `128k` / `1m`
  are detected automatically, others get a conservative default (32K), and models in the built-in
  capability table override it explicitly.
- The input budget is what remains after reserving room for the reply and an estimation margin; the
  estimator deliberately overestimates.
- Turns are packed newest-first as whole **turns**, never splitting a question from its answer. A request
  never begins with an assistant message (so the greeting never enters the request), and the most recent
  turn is always kept.
- Omitted older messages are still on the device, just no longer sent; the memory screen shows how many
  were included last time.

Estimated token counts are rough. For exact usage, read the token count returned by **Test connection**.

## Long-term memory

Every 6 new messages, `MemoryExtractor` distills durable facts (also available manually from the memory
screen) into category + key + value entries stored in `chat/cat_memory.json`. Later requests render them
as a block appended to the system prompt.

- Five fixed categories: owner, preferences, relationship, experience, current situation.
- Merging is **additive**: old entries the model did not mention are kept. Deletion only happens through
  an explicit `forget` list or by hand.
- The cap is 60 entries; over the cap, the least recently updated unpinned entries are evicted first.
- A failed extraction never affects chat, and the extraction cursor does not advance, so the same batch is
  retried automatically next time.

The heart button in the chat header opens the memory screen: view, add, edit, delete, and pin each entry,
and clear either memory or chat history. The two are independent — clearing chat history keeps memory, and
clearing memory keeps the cursor so the next extraction does not relearn what the user asked to forget.

> Prompts, merge rules, and caps are detailed in the [design document](docs/DOC_EN.md#9-structured-memory).

## Time and location

Every message stores a timestamp, and the chat stream shows times as “time only today / yesterday / date
for older”. Each request also injects a small **right now** block (`AmbientContext`): the current time
(with date and weekday), how long since the previous message, and roughly which city the owner is in.

Location uses IP geolocation (`IpLocationSource`) — **no permissions** and no dialogs. It sends the egress
IP to a third-party service in exchange for a city name, trying ip-api → ipwho.is → ipapi.co in order and
taking the first that returns a city (ip-api is the only one returning Chinese place names). The result is
cached for half an hour and refreshed only in the background. The send path only ever reads the cache — a
failed lookup just means one less line of background, never a slower message.

The trade-off must be stated plainly: accuracy is city-level; behind carrier NAT the egress IP can land in
a distant city, and behind a VPN it is the VPN's location. The prompt therefore labels it as
“inferred from the network IP, may be inaccurate”, and the settings screen has a toggle that stops all
location requests when off.

Time, location, and memory blocks are appended after the persona at the end of the system prompt: stable
prefix first, volatile last, so provider prompt caching can reuse as much as possible.

## Software updates

The "Software update" section at the bottom of the settings screen reads the latest version from
[GitHub releases](https://github.com/Lixuannan/iKitty/releases). A download is offered only when that
version is newer than the installed one; the downloaded APK is checked for package name and signature
before being handed to the system installer for an in-place update.

- The APK is downloaded to `cacheDir/updates/` and handed to the system installer through a `FileProvider`
  grant that exposes only that one file.
- If package name or signature does not match, the APK is deleted and the reason is shown. Such a package
  could not be installed anyway; saying so up front prevents someone from uninstalling first.
- **An in-place update never clears chat history**: history, images, and memory live under the app-private
  `filesDir/chat/`, and settings live in DataStore. An in-place update only replaces the code, so the data
  stays where it is; the app never uninstalls before installing.
- The first update requires allowing iKitty to install unknown apps (the `REQUEST_INSTALL_PACKAGES`
  permission).

> Version comparison, download, and verification are described in the
> [detailed design document](docs/DOC_EN.md#18-software-updates).

## Data and privacy

| Data | Location | Notes |
| --- | --- | --- |
| API settings | DataStore file `cat_settings` | Base URL, key, model, sampling parameters, provider, location toggle |
| Cat persona | Same DataStore | Name, traits, speech style, flavor, notes |
| Chat history | `filesDir/chat/chat_log.jsonl` | Plaintext JSONL, app-private directory |
| Chat images | `filesDir/chat/images/*.jpg` | Downscaled JPEG; camera temp files live in the cache and are adopted on success |
| Structured memory | `filesDir/chat/cat_memory.json` | Plaintext JSON including the extraction cursor |
| Downloaded update | `cacheDir/updates/*.apk` | Transient file, reclaimed by the system after installation |

- The app requests two permissions: `INTERNET` and `REQUEST_INSTALL_PACKAGES`; the latter is used only to
  hand the official update package to the system installer.
- There is no backend; chat content goes only to the model service you configured.
- With location enabled, the egress IP is shared with third-party geolocation services
  (ip-api / ipwho.is / ipapi.co).
- Checking for updates only reads release metadata from `api.github.com` and downloads the APK; no local
  information is reported.
- The API key is stored in plaintext in the app-private DataStore with no additional encryption — a known
  limitation.

## Model capability table

`ModelCatalog` is the single source of truth for which parameters are tunable and over what range; both
the settings screen and the request body are driven by it:

| Provider | Model | temperature | top_p | max_tokens | Reasoning | Context window |
| --- | --- | --- | --- | --- | --- | --- |
| Zhipu GLM / Z.AI | glm-5.3 | 0–1 | 0.01–1 | ≤32768 | `reasoning_effort` | 1M |
| Zhipu GLM / Z.AI | glm-5.3-flash | 0–1 | 0.01–1 | ≤32768 | `reasoning_effort` | 200K |
| DeepSeek | deepseek-v4-pro / deepseek-flash | 0–2 | 0.01–1 | ≤8192 | Not supported | 128K |
| OpenAI | gpt-5.5 / gpt-5.3-codex | Not sent | Not sent | ≤32768 | `reasoning_effort` | 400K |
| Anthropic | claude-opus-4.7 / claude-sonnet-4.6 | 0–1 | 0.01–1 | ≤8192 | Not supported | 200K |
| Google | gemini-3.1-pro / gemini-3-flash | 0–2 | 0.01–1 | ≤8192 | Not supported | 1M |
| xAI | grok-4 | 0–2 | 0.01–1 | ≤8192 | Not supported | 256K |
| DashScope | qwen3.6-max / qwen3-coder-next | 0–2 | 0.01–1 | ≤8192 | Not supported | 256K |
| Moonshot | kimi-k3 | 0–1 | 0.01–1 | ≤8192 | Not supported | 256K |
| MiniMax | MiniMax-M3 | 0–1 | 0.01–1 | ≤8192 | Not supported | 1M |
| Doubao | doubao-seed-2.0-pro | 0–1 | 0.01–1 | ≤8192 | Not supported | 256K |
| Tencent Hunyuan | hunyuan-turbos | 0–2 | 0.01–1 | ≤8192 | Not supported | 128K |
| ERNIE | ernie-x1.1 | 0–1 | 0.01–1 | ≤8192 | Not supported | 128K |
| Mistral | mistral-small-4 | 0–1 | 0.01–1 | ≤8192 | Not supported | 128K |
| OpenRouter / SiliconFlow / Ollama | llama-4-maverick | 0–2 | 0.01–1 | ≤8192 | Not supported | 1M |

Other providers and hand-typed model names go through name-based heuristics (`reasoner` / `z1` / `r1` →
always thinking, `glm-4.5`–`glm-4.9` → thinking toggle, `glm-5` and up / `o*` / `gpt-5` →
`reasoning_effort`), fall back to generic OpenAI-compatible rules, and are labeled as such in the settings
screen. Adding a provider or model means editing only this table.

The context window drives `ContextAssembler`'s token budget. The windows here follow each series' public
values: GLM-5.3 and MiniMax M3 at 1M are verified, the rest are conservative values from the previous
generation of the same series. When a vendor changes a number, edit the `window` argument at the
corresponding `ModelCatalog.builtIn` call. Models without a built-in entry still fall back to 32K.

A `max_tokens` of 0 means “unlimited, do not send”, shown as “unlimited” in the settings screen.

## Project layout

```
iKitty/
├── app/src/main/java/com/example/aicat/
│   ├── MainActivity.kt            Entry Activity and theme
│   ├── CatChatScreen.kt           Chat screen UI
│   ├── CatChatViewModel.kt        Chat state and all orchestration
│   ├── SettingsScreen.kt          Settings screen UI
│   ├── CatMemoryScreen.kt         Memory screen UI
│   ├── CatView.kt                 Canvas cat and avatar
│   ├── ChatImage.kt               Local image thumbnails
│   ├── CatState.kt                Mood / animation enums
│   ├── CatReply.kt                Model reply parsing
│   ├── CatPersona.kt              Persona -> system prompt
│   ├── CatMemory.kt               Memory model, merge rules, rendering, parsing
│   ├── CatMemoryStore.kt          Memory file read/write
│   ├── MemoryExtractor.kt         Extraction prompt and call
│   ├── ModelCatalog.kt            Providers, model capability, parameter ranges
│   ├── ChatModels.kt              Message, parameter, and config models
│   ├── ApiClient.kt               OpenAI-compatible HTTP client
│   ├── ContextAssembler.kt        Token estimation and context assembly
│   ├── ChatLogStore.kt            Append-only JSONL chat log
│   ├── StoredMessage.kt           Persisted message model
│   ├── ImageStore.kt              Image import, downscaling, data-URL encoding
│   ├── SettingsStore.kt           DataStore persistence
│   ├── AmbientContext.kt          "Right now" background block
│   ├── Location.kt / IpLocationSource.kt  IP city geolocation
│   └── TimeFormat.kt              Time and interval formatting
├── app/src/test/java/com/example/aicat/   65 plain-JVM unit tests
├── design/cat_v1/                 Layered cat character assets and spec
└── docs/DOC_EN.md                 Detailed design document
```

## Tests

```bash
./gradlew testDebugUnitTest
```

The 65 cases cover pure logic contracts: chat log read/write and corrupt-line tolerance, image-message
persistence and round-trip, memory merge and parsing, context assembly (including image tokens and image
resolution), multimodal request-body structure, persona prompt, image sampling ratio and MIME, IP response
parsing, and the “right now” block. UI, real network requests, and image decoding/compression are outside
unit-test scope. See [the design document](docs/DOC_EN.md#14-testing-strategy) for details.

## Known limitations and roadmap

1. The cat canvas is not shown on the chat screen (see [Current shape](#current-shape-chat-only)).
2. Only one provider configuration is stored — switching providers overwrites the key and model.
3. The chat screen loads only the most recent 400 messages; there is no upward pagination.
4. Messages store only UTC millisecond timestamps with no recorded timezone offset, so “what time was it
   then” is inaccurate across timezones.
5. Token counts are estimates; assembly deliberately overestimates.
6. Location accuracy is city-level and depends on third-party IP services.
7. The API key is stored in plaintext; EncryptedSharedPreferences / Keystore is not wired in.
8. UI strings are Chinese only; no localization resources yet.
9. Images are downscaled and re-encoded as JPEG: lossy, with transparent areas flattened to white, and at
   most 9 per message.
10. Historical images are re-sent every turn, so many images noticeably enlarge the request body and traffic.
11. Images can only be viewed inside the app — no full-screen viewer, save-to-gallery, or zoom.

Planned work: put the cat canvas back (optionally toggled), move to Rive/Lottie animation, save per-provider
configurations, paginate history upwards, record the timezone offset at write time, add a system-location
`LocationSource` implementation (runtime permissions and failure fallback required), add a full-screen image
viewer, and add retrieval (message chunking + vectors) when “never forget” is genuinely needed.

## Related documents

- [docs/DOC_EN.md](docs/DOC_EN.md): architecture, module contracts, data formats, algorithms, extension points
- [design/cat_v1/README.md](design/cat_v1/README.md): coordinate system, layers, and driving suggestions for the cat assets

## License

The repository currently contains no `LICENSE` file.
