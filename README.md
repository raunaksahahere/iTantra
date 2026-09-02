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
| Someone in the group can't read or type | They just hold a button and talk |

## Features

- **Speech → text → speech transceiver.** Hold to talk on one phone, hear it on another. Only text crosses the link, so a spoken sentence costs a few hundred bytes instead of tens of kilobytes.
- **Ten Indian languages.** Hindi and English work out of the box; the other eight install as one-time language packs.
- **Everything runs on the device.** Speech recognition, speech synthesis and voice detection all execute locally with ONNX Runtime. Nothing is sent anywhere for processing, and the whole loop works in airplane mode.
- **Bluetooth LE mesh transport.** Built on the [bitchat](https://github.com/permissionlesstech/bitchat-android) stack — automatic peer discovery, multi-hop relay, no pairing, no accounts. Phones out of direct range are reached through the phones in between.
- **End-to-end encryption.** Noise Protocol sessions for private messages; broadcast messages are signed, and secure-versus-broadcast is the sender's explicit choice.
- **Voice and text in one thread.** Every message exists as both. Type when you can't speak, tap the speaker icon to hear any received message read aloud, or let it auto-speak as it arrives.
- **Alert mode.** Urgent messages announce on the alarm stream at maximum volume and cannot be interrupted — they get through even if the receiver's phone is on silent in a pocket.
- **Voice-activity gating.** Silero VAD listens for actual speech instead of running recognition continuously, which is what keeps idle battery drain reasonable.
- **Lightweight local identity.** You pick a display name; the app generates a Curve25519 keypair on first launch and derives a peer ID from its fingerprint. Private keys live in the Android Keystore and never leave the device. Two identical phone models in the same room stay distinguishable, and the sender can see each peer's device model.
- **On-demand model provisioning.** Language packs download once, verified by SHA-256 against a signed manifest, with a mirror fallback. The Model Manager refuses to install a file it cannot verify. One language stays resident in RAM at a time, so the app stays usable on low-end hardware.

### Languages

| Language | | Code | Availability |
|---|---|---|---|
| Hindi | हिन्दी | `hi` | Bundled |
| English | English (Indian) | `en` | Bundled |
| Bengali | বাংলা | `bn` | Language pack |
| Gujarati | ગુજરાતી | `gu` | Language pack |
| Kannada | ಕನ್ನಡ | `kn` | Language pack |
| Malayalam | മലയാളം | `ml` | Language pack |
| Marathi | मराठी | `mr` | Language pack |
| Odia | ଓଡ଼ିଆ | `or` | Language pack |
| Tamil | தமிழ் | `ta` | Language pack |
| Telugu | తెలుగు | `te` | Language pack |

## How it works

```
   ┌─ speaking phone ──────────────┐         ┌─ listening phone ─────────────┐
   │                               │         │                               │
   │  mic → VAD → speech-to-text   │  text   │  text-to-speech → speaker     │
   │                               │ ──────► │                               │
   │      IndicConformer (ONNX)    │  BLE    │   FastPitch + HiFi-GAN (ONNX) │
   └───────────────────────────────┘  mesh   └───────────────────────────────┘

              only text crosses the link — never audio
```

Silero VAD decides when you are actually speaking, so recognition doesn't run on silence. IndicConformer transcribes with greedy CTC decoding. The text is wrapped in a compact payload — message id, type, source language, sender name, peer id, device model, timestamp — and handed to the bitchat mesh, which relays it hop by hop to peers out of direct range. On the far side, FastPitch generates a mel spectrogram and HiFi-GAN turns it into audio.

Everything is open source: Kotlin and Jetpack Compose for the app, ONNX Runtime Mobile for inference, bitchat for transport, and MIT-licensed models throughout.

## Design decisions worth knowing

- **No audio on the wire, ever.** Not compressed, not as a fallback. The entire bandwidth argument collapses the moment audio is serialized into a payload, so it simply isn't allowed.
- **Offline means offline at runtime.** The only network code in the app is the Model Manager, and it only runs for one-time language-pack downloads. There is no hosted API anywhere in the speak → transmit → hear loop. Provisioning a language pack is treated like downloading an offline map.
- **Nothing unverified gets installed.** A model file is installable only when both its URL and its SHA-256 are published in the manifest. Missing hash, no install.
- **Translation stays optional.** The voice loop never depends on it, so translation can be absent or slow without taking the core feature down with it.

## Project status

Actively in development. The app is built and the pipelines are wired end to end, but it is **not yet a working voice demo**, for one specific reason:

- ✅ **Working** — BLE mesh transport, identity and peer discovery, typed text messaging, read-aloud, alert mode, push-to-talk capture, language selection, Model Manager with SHA-256 verification, Silero VAD (bundled and running).
- ⏳ **Blocked** — speech recognition and synthesis are code-complete and wired, but upstream publishes IndicConformer and Indic-TTS as NeMo/PyTorch checkpoints rather than mobile-ready ONNX. Each language still has to be exported and quantized before it can run. Until that lands, the engines report "model missing" rather than failing silently.
- 📋 **Planned** — offline translation between languages (IndicTrans2, no code yet), and bypass mode for raw audio with recognition and synthesis switched off.

Silero VAD is currently the only model shipping as a working binary.

## Acknowledgements

Built on the work of [bitchat](https://github.com/permissionlesstech/bitchat-android) (mesh transport), [AI4Bharat](https://ai4bharat.iitm.ac.in/) (IndicConformer, Indic-TTS and IndicTrans2), and [Silero](https://github.com/snakers4/silero-vad) (voice activity detection).
