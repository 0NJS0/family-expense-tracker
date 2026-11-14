# Voice entry: evaluation (Milestone 5)

Status: **evaluated, not built.** Recommendation below needs the owner's
decision and a test on a phone.

## Requirement

Bangla (and English) voice entry, e.g. "বাজারে পাঁচশো টাকা", turned into a
*proposal* (amount, category) that the person confirms. No cloud AI.

## Options

| Option | Privacy | Bangla quality | Cost | Verdict |
|--------|---------|----------------|------|---------|
| Android `SpeechRecognizer` (default) | **Audio goes to Google** | good | none | Not allowed (no cloud) |
| Android on-device recognizer (`createOnDeviceSpeechRecognizer`, Android 12+) | stays on the phone | depends on an offline Bangla pack being installed; varies by phone maker | small | **Possible**; must be checked on the owner's Redmi (MIUI) |
| Whisper (whisper.cpp) on the homelab | stays on the home network | small/base models are weak in Bangla; large models are too slow on the homelab CPU (the LLM there already takes ~1 s per token) | medium–high | Not now |
| Vosk / other offline models | stays local | no maintained Bangla model found | medium | Not now |

## The harder half: understanding the words

Even with a perfect transcript, "পাঁচশো", "দেড় হাজার", "আড়াইশো" and mixed
Bangla/English must become exact poisha, and "বাজার"/"ওষুধ" must map to a
category. That needs a careful, fully tested Bangla number-word parser (the
money rules allow no guessing). Budget roughly one milestone increment.

## Recommendation

1. On the Redmi, check `SpeechRecognizer.isOnDeviceRecognitionAvailable()` and
   whether an offline Bangla pack can be installed. If not, voice entry would
   not work for the main user, and it should stay unbuilt.
2. If it works: build on-device recognition only (never the cloud fallback),
   a tested Bangla number-word parser, and show the result as a filled-in
   form that still needs "Save". The big amount field and quick-amount buttons
   remain the primary way to enter.
