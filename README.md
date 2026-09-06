<img width="200" height="200" alt="iTantra" src=".github/assets/itantra.png" />

## iTantra

A fully offline, multilingual voice transceiver for Android. Speak into one phone and your words come out of another phone's speaker — across a Bluetooth mesh, with no internet, no cell tower, no servers and no accounts.

The trick is that **your voice never crosses the link.** The speaking phone turns speech into text on-device, only that text travels over the mesh, and the listening phone turns it back into speech on-device. A sentence of audio is tens of kilobytes; the same sentence as text is a couple of hundred bytes. That is what makes real-time voice possible over a link as thin as Bluetooth LE.

> Indian Multilingual TTS & STT Aided Neural Transceiver for Low-Bitrate Links

## Why it exists

When a flood takes out the towers, or you are three valleys past the last bar of signal, the phone in your pocket becomes a camera. Everything that makes it useful for talking to people assumes infrastructure that is exactly what disappears first.

Radio handsets solve this, but they are extra hardware somebody has to own, carry and charge — and they do not speak Marathi to a Tamil speaker.

Voice is also the most inclusive interface there is. It works whether or not you can read, whether or not you can type in your own script, and whether or not you have ever used a messaging app. A tool meant for the worst day of someone's life should not require literacy as a prerequisite.

iTantra turns ordinary Android phones into walkie-talkies that work with no infrastructure at all, in ten Indian languages.

## Who it's for

- **Disaster and emergency responders** — search parties, relief coordinators and volunteers working in areas where the network is down or saturated. Teams that were handed phones, not radios.
- **Rural and remote communities** — villages, forests, hills and border areas where coverage is thin on a good day and absent on a bad one.
- **Anyone who needs to be understood across a language line** — a Malayalam-speaking medic and a Bengali-speaking family, without either of them typing.
- **Non-literate users** — the loop starts and ends in speech. You never have to read anything to use it.

## When you'd reach for it

| Situation | What iTantra gives you |
|---|---|
| Cell network is down after a flood, quake or cyclone | Phone-to-phone voice with zero infrastructure |
| Trekking, forest patrol, remote fieldwork | Group comms out of coverage, no radios to carry |
| Crowded event where the network is congested | A channel that does not depend on the tower at all |
| Relief camp with mixed-language teams | Speak your language, be heard in theirs |
| Broadcasting an urgent instruction to everyone nearby | Alert mode: max volume, cannot be silenced by the receiver's ringer settings |
| Trapped, hurt, or separated from the group | One-tap distress call that keeps propagating for an hour and reaches people who arrive later |
| Someone in the group can't read or type | They just hold a button and talk |

## Features

- **Speech → text → speech transceiver.** Hold to talk on one phone, hear it on another. Only text crosses the link, so a spoken sentence costs a few hundred bytes instead of tens of kilobytes.
- **Ten languages.** Hindi and English are ready to install today; the rest are one-time language packs. Models are downloaded once rather than bundled — a single language is a few hundred megabytes, which is not something to put in an APK.
- **Everything runs on the device.** Speech recognition, speech synthesis and voice detection all execute locally with ONNX Runtime. Nothing is sent anywhere for processing, and the whole loop works in airplane mode.
- **Bluetooth LE mesh transport.** Built on the [bitchat](https://github.com/permissionlesstech/bitchat-android) stack — automatic peer discovery, multi-hop relay, no pairing, no accounts. Phones out of direct range are reached through the phones in between.
- **Signed by default, encrypted on request.** Every message is Ed25519-signed, so a relay can't forge or tamper with one — but a public broadcast is readable by the phones that carry it, which is the price of reaching furthest. Flip the lock and messages go out Noise-encrypted end to end to each peer instead. It's the sender's explicit choice per conversation, and the interface says which mode you're in rather than leaving you to assume.
- **Everyone hears their own language.** A sender speaks and transmits in *their* language and never translates. Each receiving phone translates the incoming text into whatever language *it* is set to, then speaks it. One broadcast therefore reaches a Hindi speaker and an English speaker at the same time, each hearing their own — which is only possible because translation happens on the receiving side. Hindi ↔ English today.
- **Voice and text in one thread.** Every message exists as both. Type when you can't speak, tap the speaker icon to hear any received message read aloud, or let it auto-speak as it arrives.
- **Distress announcements.** A one-tap SOS broadcast to everyone in range, carrying your coordinates if the phone has a fix. It keeps propagating for an hour, and every phone holding it re-announces to people it newly meets — so a search party arriving forty minutes later still hears it. You can mark it resolved at any point. Tapping an announcement shows the origin alongside your own position and the distance between them.
- **Conversations start with a person.** The home screen is a searchable list of everyone this phone can reach — directly linked phones *and* phones reachable through other phones, each tagged `direct` or with a hop count. You pick someone, then talk; there is no "shout at everyone nearby" button, because addressing a message to a person is almost always what you meant. Distress is the deliberate exception and sits on its own, outside the list.
- **Alert mode.** Urgent messages announce on the alarm stream at maximum volume and cannot be interrupted — they get through even if the receiver's phone is on silent in a pocket.
- **Voice-activity gating.** Silero VAD listens for actual speech instead of running recognition continuously, which is what keeps idle battery drain reasonable.
- **Lightweight local identity.** You pick a display name; the app generates a Curve25519 keypair on first launch and derives a peer ID from its fingerprint. Private keys live in the Android Keystore and never leave the device. Two identical phone models in the same room stay distinguishable, and the sender can see each peer's device model.
- **On-demand model provisioning.** Language packs download once, each file checked against a SHA-256 recorded in a manifest that ships inside the app, with a mirror to fall back on. A file whose hash isn't published simply won't install — there is no "trust it anyway" path. One language stays resident in RAM at a time, so the app stays usable on low-end hardware.

### Languages

Languages are multi-select — enable as many as you need and switch between them mid-conversation without restarting.

| Language | | Code | Recognition | Synthesis | Translation |
|---|---|---|---|---|---|
| Hindi | हिन्दी | `hi` | Ready | Ready | Ready (↔ English) |
| English | English (Indian) | `en` | Ready | Ready | Ready (↔ Hindi) |
| Bengali | বাংলা | `bn` | Ready | Exportable | — |
| Gujarati | ગુજરાતી | `gu` | Ready | Exportable | — |
| Kannada | ಕನ್ನಡ | `kn` | Ready | Exportable | — |
| Malayalam | മലയാളം | `ml` | Ready | Exportable | — |
| Marathi | मराठी | `mr` | Ready | Exportable | — |
| Tamil | தமிழ் | `ta` | Ready | Exportable | — |
| Telugu | తెలుగు | `te` | Ready | Exportable | — |
| Odia | ଓଡ଼ିଆ | `or` | **None published** | Exportable | — |

*Ready* means a verified quantized model exists and the app can install it. *Exportable* means the upstream checkpoint is published and `model-export/` turns it into a mobile model in about a minute per language. Odia is the one gap: AI4Bharat has not published a recognition model for it in a form anyone has exported yet.

Translation is currently Hindi ↔ English only, because those are the two languages with working recognition *and* synthesis — translating into a language the phone cannot speak would not produce audio. A phone only carries the direction *into* its own language, so an English handset holds Hindi→English and nothing else.

## How it works

```
   ┌─ speaking phone ──────────────┐         ┌─ listening phone ─────────────────────┐
   │                               │ text +  │                                       │
   │  mic → VAD → speech-to-text   │ source  │  translate → text-to-speech → speaker │
   │                               │  lang   │                                       │
   │      IndicConformer (ONNX)    │ ──────► │  IndicTrans2 → FastPitch + HiFi-GAN   │
   └───────────────────────────────┘   BLE   └───────────────────────────────────────┘
                                      mesh
              only text crosses the link — never audio
        the sender never translates; every receiver translates for itself
```

Silero VAD decides when you are actually speaking, so recognition doesn't run on silence. IndicConformer transcribes with greedy CTC decoding. The text is wrapped in a compact payload — message id, type, source language, sender name, peer id, device model, timestamp — and handed to the bitchat mesh, which relays it hop by hop to peers out of direct range.

On the far side the receiver reads the source-language tag and, if it differs from its own, runs IndicTrans2 (encoder–decoder, greedy decoding) to translate before speaking. Then FastPitch generates a mel spectrogram and HiFi-GAN turns it into audio. When the languages already match, translation is skipped entirely and no model is loaded.

Everything is open source: Kotlin and Jetpack Compose for the app, ONNX Runtime Mobile for inference, bitchat for transport, and MIT-licensed models throughout.

## Building it

Android Studio, or from the command line:

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

Requires JDK 17 and a `local.properties` pointing at your Android SDK. The debug build
targets `arm64-v8a` and `armeabi-v7a` only — the x86 variants exist for emulators, and an
emulator cannot exercise a Bluetooth mesh, so they are left out rather than adding tens of
megabytes of unused native code.

### Getting speech onto a phone

The APK ships the voice-activity detector but not the recognition and synthesis models,
which are far too large to bundle. Exported models are not in the repository either — run
the export first (`model-export/README.md`), then install them once per device:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell mkdir -p /sdcard/Android/data/com.itantra/files/models/hi
adb push model-export/out/hi/. /sdcard/Android/data/com.itantra/files/models/hi/

# translation, if you want cross-language delivery. A phone needs only the
# direction *into* the language it is set to:
adb push model-export/mt/staged/hi/. /sdcard/Android/data/com.itantra/files/models/hi/
adb push model-export/mt/staged/en/. /sdcard/Android/data/com.itantra/files/models/en/
```

Open **Language Packs** — an installed language reads *Ready*. Recognition models can also
be downloaded from inside the app, which is the path a real user takes; the `adb push`
route exists so a language can be tested before it is hosted anywhere.

`model-export/README.md` has the full checklist, how to export the remaining languages,
and the pinned dependency versions the export needs.

## Design decisions worth knowing

- **No audio on the wire, ever.** Not compressed, not as a fallback. The entire bandwidth argument collapses the moment audio is serialized into a payload, so it simply isn't allowed.
- **Offline means offline at runtime.** The only network code in the app is the Model Manager, and it only runs for one-time language-pack downloads. There is no hosted API anywhere in the speak → transmit → hear loop. Provisioning a language pack is treated like downloading an offline map.
- **Nothing unverified gets installed.** A model file is installable only when both its URL and its SHA-256 are published in the manifest. Missing hash, no install.
- **Translation happens on receive, never on send.** The sender transmits in its own language and tags the packet. If it translated before sending it would have to pick *one* target language, and the broadcast would stop being multilingual — one Hindi utterance can reach a Hindi speaker, an English speaker and a third phone with no translation model at all, and each does the right thing locally. It also means a phone only ever stores the direction into its own language.
- **A failed translation says so.** Untranslated text is never presented as though it had been translated — the message is labelled. In a distress message a wrong or silently-untranslated line is worse than an obviously missing one.
- **Translation stays optional.** The voice loop never depends on it, so translation can be absent or slow without taking the core feature down with it.
- **Distance is measured in hops first, GPS second.** Reach is capped by hop count, which works with no satellite fix at all — and no fix is the normal case inside a collapsed building or a basement. A GPS fix, when both ends have one, can only *tighten* that boundary, never widen it. Hop count is an approximation of distance, not a guaranteed range in kilometres: a Bluetooth hop might be ten metres through concrete or a hundred across open ground, which is why the interface says "3 hops away" rather than inventing a distance.

## Project status

Actively in development. Every piece of the voice loop now exists as a runnable model, and the app is wired end to end — but **it has not yet been run on real phones**, which is the honest bar for a project like this.

- ✅ **Built and verified on the desktop** — BLE mesh transport, identity and peer discovery, peer-first navigation, typed text, read-aloud, alert mode, push-to-talk capture, multi-select languages, distress announcements, multi-hop peer visibility, targeted messaging, and the Model Manager with SHA-256 verification. Recognition, synthesis and translation models exist for Hindi and English and produce correct output when driven directly — Hindi↔English translation was checked sentence by sentence, and the tokenizer reimplementation was diffed piece-for-piece against the reference SentencePiece library before it was trusted.
- ⏳ **Not yet proven on hardware** — two-phone discovery, mesh delivery, distress propagation across relays, push-to-talk capture, and end-to-end spoken latency including translation. None of these can be honestly claimed from a desktop, and BLE in particular behaves differently on real radios and per-manufacturer power management.
- 📋 **Planned** — translation beyond Hindi ↔ English, KV-cached decoding to speed translation up, bypass mode for raw audio, and recognition for Odia.

Recognition models come from published Apache-2.0 exports of the AI4Bharat weights, so nothing needs exporting for the nine languages that have them. Synthesis is exported locally by the tooling in `model-export/`, which produces roughly 85 MB per language after int8 quantization. Translation likewise needed no export — MIT-licensed ONNX conversions of IndicTrans2 already exist — and costs roughly 230–270 MB for the one direction a given phone needs.

Translation currently uses greedy decoding without a key/value cache, so the decoder re-runs the whole prefix at every step. That is the obvious thing to optimise, and it is a speed limitation rather than a correctness one.

No performance numbers are quoted anywhere in this README, because none have been measured on a phone yet.

## Acknowledgements

Built on the work of [bitchat](https://github.com/permissionlesstech/bitchat-android) (mesh transport), [AI4Bharat](https://ai4bharat.iitm.ac.in/) (IndicConformer, Indic-TTS and IndicTrans2), and [Silero](https://github.com/snakers4/silero-vad) (voice activity detection).

Speech recognition runs on the [`indicconformer-sherpa-onnx`](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx) exports of the AI4Bharat models, which saved this project from having to export nine languages itself. Translation runs on the MIT-licensed [`indictrans2-*-dist-200M-onnx`](https://huggingface.co/TigreGotico) conversions, which saved a second export pipeline.
