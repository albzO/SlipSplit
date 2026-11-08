# Changelog

**English** | [中文](CHANGELOG.zh.md)

## 1.1 — 2026-11-09

### Changed
- Receipt corners are found from the U²-Netp mask in plain Kotlin. OpenCV and its edge-detection fallback are gone; when no corners are found the whole photo is kept for you to frame.
- On photos with a bag or a second slip in view, the frame now covers the whole receipt instead of cutting off a corner.
- Release builds are minified with R8 and shrink unused resources.
- The Medium recognition model is no longer bundled; Tiny and Small remain.
- The APK is about 101 MB, down from 135 MB.
- The project folder moved from `android-bridge/` to `SlipSplit/`.

### Fixed
- Digit correction only accepts a fix that matches the printed total to the cent. Before, a fix one cent off could win, such as reading a bag's $0.10 as $0.19 instead of correcting two faded 8s.

## 1.0 — 2026-11-09

First release of the bill splitter.

### Added
- Jetpack Compose app: home, corner crop, recognition, split, pick from photo, summary and settings, with transitions between screens.
- Live recognition progress, line by line, with a second pass when rows are slanted.
- Misread digits corrected against the printed total and shown on the zoomed photo for you to check.
- Split screen: tap items to assign them, shared items split evenly, unpicked items go to the last person, whole-bill charges shared by percentage.
- Summary copied as text or shared as an image.
- History: swipe up on the home screen, tap a split to edit it, long-press to delete.
- English and Chinese interface, following the system language unless set in Settings.
- App icon.

### Changed
- Recognition records are off by default.
- DewarpNet was removed; flattening is UVDoc plus row levelling.
