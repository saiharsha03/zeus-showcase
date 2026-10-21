# Voice: hybrid

Code: `app/src/main/java/dev/phonerecall/voice/`.

## Entry and routing
- Push-to-talk only. The side key (default assistant) or the Speak button opens the on-device recognizer. There is no hotword and nothing listens by itself.
- `VoiceRouter.route` decides per final recognizer result:
  - **Quick command** (fixed parser, `QuickCommands.parse`): timers, alarms, music pause/resume/next/previous, "play X", "add X to groceries", "what's next". Runs on the phone.
  - **Not a known command**: Nano (on device, foreground only) gets one chance to name one of the quick tools (`QuickCommands.fromNano`). Otherwise the words go to Athena (the chat), the same as typed text.
  - **"Let's talk"** (and similar phrases), or a question of 14 or more words: Gemini Live. A long question goes to Athena instead when Live isn't available (no key, or no minutes left).
- Typed text goes to Athena, as in the chat.

## On-device path
- `SpeechRecognizer` (the on-device recognizer when the phone has one) with `EXTRA_PREFER_OFFLINE` set. This is the existing `rememberVoiceCapture`.
- Quick commands call the phone's own `ToolBox.run`, so every write goes through `Fates.propose` and becomes a card. The auto-approve list is respected and is empty by default.
- Spoken input gets spoken replies through the phone's `TextToSpeech` engine. Athena's replies to spoken input are spoken too.

## Gemini Live
Sources:
- https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket (raw WebSocket URL, setup, realtimeInput and toolResponse JSON)
- https://ai.google.dev/api/live (message reference)
- https://ai.google.dev/gemini-api/docs/live (audio formats)
- https://ai.google.dev/gemini-api/docs/live-guide (interruptions, transcription)
- https://ai.google.dev/gemini-api/docs/live-tools (function calling)
- https://ai.google.dev/gemini-api/docs/pricing (rates)

| What | Value |
|---|---|
| Endpoint | `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent` |
| Model | `gemini-3.8-live` (the model the docs use in their examples; `gemini-3.8-live-extended-thinking` also exists) |
| Auth | Zeus sends the key in the `x-goog-api-key` handshake header. The WebSocket guide puts it in `?key=` instead; the header keeps it out of URLs and logs. **Unverified on device.** If the header is refused, switch `LiveApi.handshake` to `?key=` (`NetGate.redact` already removes query strings). |
| First frame | `{"setup":{"model":"models/gemini-3.8-live","generationConfig":{"responseModalities":["AUDIO"]},"systemInstruction":{"parts":[{"text":…}]},"tools":[{"functionDeclarations":[…]}],"inputAudioTranscription":{},"outputAudioTranscription":{}}}`, then wait for `setupComplete` |
| Mic | `{"realtimeInput":{"audio":{"data":<base64>,"mimeType":"audio/pcm;rate=16000"}}}`: 16-bit LE mono PCM at 16 kHz, in 40 ms frames (1280 bytes) |
| Voice out | `serverContent.modelTurn.parts[].inlineData.data`: base64 16-bit LE PCM at 24 kHz, played through an `AudioTrack` |
| Barge-in | Gemini's own voice detection sends `serverContent.interrupted`, and Zeus drops the queued audio. The mic is a `VOICE_COMMUNICATION` source with the echo canceller on, so Gemini doesn't hear itself. |
| Tools | `toolCall.functionCalls[]` `{id,name,args}` → `{"toolResponse":{"functionResponses":[{id,name,response:{result\|error}}]}}` |
| End | `goAway` ends the session, with a line on the bar |
| Price (paid) | $0.005/min audio in, $0.018/min audio out. The free tier costs nothing, but Google may use those conversations. |

The API reference places `responseModalities` inside `generationConfig`, while the WebSocket guide's example puts it at the top of `setup`. Zeus follows the reference. **Unverified on device.**

### What Gemini sees
- A short system instruction with the date and time, and the rule that every action only proposes.
- Function declarations that mirror the phone's read tools (`now`, `read_calendar`, `read_tasks`, `read_closet`, `read_home_cards`, `read_availability`, `search_memory`, `read_music`) and its proposal tools (`set_alarm`, `set_timer`, `add_reminder`, `add_task`, `complete_task`, `add_calendar_event`, `create_card`, `add_to_list`, `play_music`, `music_control`).
- Never Calypso or Aegis: no `calypso_deposit`, `aegis_list` or `aegis_read`. Never `hand_off`, which starts paid background work. Never raw Odyssey, health or money data, because no tool returns them.
- `propose_plan` is left out because its free-form step input has no schema Gemini accepts. Gemini calls the single tools instead.
- `LiveToolBridge` refuses any name outside this list, whatever Gemini asks for. Writes go through `ToolBox` → `Fates.propose`, so they become cards. Gemini can never act directly.

### Keys
- The key is a new Calypso row, "Gemini key", stored in `SealedSecret` slot `gemini` under the Calypso Keystore key.
- Only `LiveSession.start` reads the full key, and it goes only into the handshake header to the Live host.
- `KeyGuard` checks every outgoing frame and refuses one that contains the Gemini key or the Anthropic key.

### NetGate
- `generativelanguage.googleapis.com` is not in `NetGate.HOSTS`. `NetGate.allows` lets through only the Live path, only while `NetGate.liveOpen` is true (between `LiveSession.start` and `stop`), on port 443 with no user info, over https or wss. OkHttp reports the socket URL as https.
- The REST API on the same host stays blocked.
- `network_security_config.xml` trusts the system CAs for that host.

### Cap and spend
- Themis has "Live minutes a day" (15 / 30 / 60, default 30). A session stops by itself when it reaches the cap.
- The first Live of each day shows a confirm on the sheet, with the cost and the minutes left.
- Themis shows Live's minutes and its estimated spend at paid rates right under Athena's spend. They are separate from Athena's $100 cap.

### Driving
When `MusicCore.driving` is set (DriveSense: "Detect driving" in Themis, or Odyssey tracking), the sheet keeps only:
- the Live bar or her last line, in large type
- a full-width Stop
- Speak and Live

Replies are spoken. Live speaks anyway.

## What the owner must do
1. Get a Gemini API key at aistudio.google.com (the free tier works to start).
2. Zeus → Calypso → **Gemini key** → Add key.
3. Open the sheet with the side key, say "let's talk", and confirm the first Live of the day.

## Not built yet or unverified
- **Live has not run against Google.** The protocol is checked against the docs only.
- Unverified until it runs on the phone:
  - the `x-goog-api-key` header on the WebSocket
  - `responseModalities` inside `generationConfig`
  - Gemini accepting lowercase JSON-schema types in `parameters`
- No session resumption or context-window compression. Audio-only sessions have a time limit, and `goAway` ends the session.
- No Piper or Kokoro voice. TextToSpeech uses the phone's engine.
- Nano's intent guess works only while Zeus is in the foreground (AICore's rule). The sheet is in the foreground.
- No "deep thinking" handoff from Live to Claude in the middle of a conversation. Long questions go to Live, and a short non-command goes to Athena.
