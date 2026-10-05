# Collage Video (Android)

Pick a **collage or group photo** and a **reference video**. The app makes a **new video** in which the people from
the photo are the people in the video: the reference only gives the movement, the choreography, the camera and the
scene. Everything runs on the phone and works offline after the first-run model download.

- Package `com.vanu.collagevideo` (new app, installs next to Couple Face Swap, Face Swap Video and GIF Face Swap)
- Version 1.0 (versionCode 1), Android 7.0+ (API 24), arm64-v8a and armeabi-v7a APKs
- APK about 17 MB; the AI models are downloaded once from this repo's
  [`models-v1` release](https://github.com/vanukrishnans-source/collage-video/releases/tag/models-v1)

## How to use

1. Install `CollageVideo-1.0-arm64.apk` (use `armeabi-v7a` only on old 32-bit phones). Open it and tap **Download**.
   The download is 566 MB, needs Wi-Fi once, can be paused and resumed, and every file is checked against its SHA-256.
2. **Collage**: pick the photo with the people. Faces are found (small faces too, via tiled scanning) and numbered
   in reading order. Tap a face to leave it out.
3. **Video**: pick the reference video and the part to use (max 30 s). Choose 10 or 15 fps or Original, and Face detail
   (Off, Light GPEN-256 or HQ GPEN-512, both optional downloads).
4. **Take from the collage**: Face, Hair colour, Skin tone, Outfit colours. All are on by default.
5. Tap **Create collage video**. The job runs as a foreground service: you can leave the app, and progress shows in the
   notification. The output is H.264 MP4, the reference sound is copied through, and the result is at most 720p.
6. Result: play it, then **Save** (Movies/CollageVideo) or **Share**. **Flip** (2 people) or **Shuffle** changes who
   becomes who. **Who becomes who** lets you pick the collage person for every person in the video (or Keep), then
   tap *Make it again*. Re-renders reuse the first analysis.

## What it does (per video)

1. **Collage**: per person, ArcFace identity of the face, plus colour statistics (CIE Lab mean/std) of their hair,
   their skin (face above the nose + neck/arms) and their top (clothes below the chin). Each pixel is assigned to
   the nearest face, so neighbouring collage tiles don't mix. The parts come from the MediaPipe multiclass segmenter.
2. **Pass 1 (analysis)**: MediaPipe Pose (multi-person, up to 6, with body masks) finds and outlines every performer in
   every frame. Faces are found on the whole frame plus a close look around every performer's head, which matters
   for small faces in wide shots. Every 0.5 s the performers' hair, skin, top and lower clothes are measured, and the
   safety filter checks the frame.
3. **Performers** are tracked over time and get collage people **left to right**: the biggest people visible
   together, with left and right averaged over the clip so one bad frame can't swap them.
4. **Pass 2 (render)**, per frame:
   - Each performer's **hair, skin and outfit are recoloured** to their collage person's colours, matched to the
     scene's light (exposure from skin, reference lighting, shading and folds kept).
     - A dress (top and bottom the same colour) is recoloured whole.
     - Trousers or jeans are kept when the collage doesn't show them.
     - A colour gate stops a mask that spills onto the other person from recolouring them.
   - Then the collage person's **face identity** goes onto the performer's face (inswapper 128 on smoothed landmark
     tracks, optional GPEN), and the frame is encoded straight away.

## Models (first-run download 566 MB, plus optional enhancers)

| File | Size | Use | Licence |
|---|---|---|---|
| inswapper_128_fp16.onnx | 278 MB | face identity transfer | InsightFace, non-commercial |
| arcface_w600k_r50.onnx | 174 MB | face identity embedding | InsightFace, non-commercial |
| nsfw_vit_int8.onnx | 89 MB | safety filter (int8 of Falconsai nsfw_image_detection) | Apache-2.0 |
| selfie_multiclass_256x256.tflite | 16 MB | hair / skin / clothes parts | MediaPipe, Apache-2.0 |
| pose_landmarker_full.task | 9 MB | performers' pose + body masks | MediaPipe, Apache-2.0 |
| gpen_bfr_256.onnx (optional) | 76 MB | Light face detail | GPEN, research use |
| gpen_bfr_512.onnx (optional) | 284 MB | HQ face detail | GPEN, research use |

The face landmarker (3.7 MB) is inside the APK. The ONNX face models are also mirrored from the FaceFusion
GitHub/Hugging Face repositories, and the MediaPipe models from Google's model storage (same checksums).

## Safety filter (same policy as the image apps)

The safety filter is **always on and can't be switched off**:
- It checks the collage photo, plus one reference frame every 0.5 s (at most about 60 checks).
- If anything is flagged, **no video is made** and the job stops at the first flagged frame.
- *Standard* blocks at NSFW score > 0.5. *Relaxed* blocks at > 0.85 and is for false positives such as swimwear or
  beach photos.

## Honest limits

- **Not generative body synthesis.** The bodies, body shapes, poses, hairstyle shape, clothing cut and patterns, and the
  scene are the reference video's. The collage gives the faces (identity), hair colour, skin tone and outfit colours.
  A head-and-shoulders collage has no legs or full outfit to copy, so lower clothes are kept unless the reference
  outfit is one garment.
- **Faces turned away or in profile** (roughly more than 60–70°) can't be detected or swapped. In those frames only
  hair, skin and outfit colours change, so the person can briefly look like "the reference actor in new colours".
- **Very small faces** (under ~40 px at 720p) and fast motion lower the face likeness. The inswapper works at 128 px;
  Light/HQ face detail helps.
- **Patterns, logos and multi-colour outfits** become a single tint of the collage colour. Recolouring follows the
  reference's lighting, so a white garment turned dark stays a little lighter than in the collage.
- When the pose model briefly loses a person (crossing, occlusion), the last mask is reused for up to 3 frames, and
  after that that person isn't recoloured until they are found again.
- **Speed**: about 1–3 s per frame on a recent phone (pose + parts + face swap per person). A 10 s clip at 15 fps
  (150 frames) takes about 3–8 minutes. Output is at most 720p and 30 s.
- InsightFace and GPEN models are for **personal, non-commercial use**.

## Build

```
export JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk
./gradlew :app:testReleaseUnitTest :app:assembleRelease
```

Signing reads `keystore.properties` (not in the repo). `reference/collage_pipeline.py` is the desktop reference
implementation (Python, MediaPipe and ONNX Runtime) that the Kotlin pipeline mirrors. It was used for the trial render.
