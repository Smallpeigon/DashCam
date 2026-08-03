# Android Dashcam App

Native Android MVP skeleton for the mobile dashcam app.

Current prototype includes:

- Real CameraX preview and MP4 recording path.
- Camera permission and microphone permission flow.
- Multi-camera picker using Camera2 camera IDs.
- Saved default camera selection.
- 1080p / 720p recording quality picker with saved default.
- Portrait/landscape-aware visible timestamp watermark position.
- Evidence clip metadata model.
- SHA-256 hash generation after recording finalizes.
- JSON metadata export beside each recorded clip, including selected camera ID and quality.

Notes:

- Some Android phones expose multiple rear lenses as separate camera IDs, while some expose them as one logical camera. This implementation lists the camera IDs CameraX reports on the device.
- The visible timestamp overlay is shown in the app preview. A later step should burn the timestamp into exported video frames if the evidence workflow requires the watermark to be part of the actual pixels.

Next implementation step:

- Add loop recording and protected clips.
- Add bitrate tuning and storage limit controls.
- Add GPS metadata and optional location watermark.
- Add a proper settings screen instead of compact controls over the preview.
