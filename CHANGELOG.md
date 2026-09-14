# Changelog

All notable changes to Autobile are documented here. Releases follow semantic versioning.

## [0.6.1] - 2026-09-15

### Fixed

- Discover models supported by the signed-in ChatGPT subscription instead of relying on
  retired preset identifiers.
- Migrate v0.6.0 subscription model defaults while preserving custom overrides.
- Report unavailable models and malformed requests separately from unsupported images.

## [0.6.0] - 2026-09-14

### Added

- Continue visual grounding when an app exposes no accessibility tree.
- Preserve tap, long-press, swipe, and scroll actions returned by visual grounding.
- Validate rootless actions with a fresh screenshot.

### Fixed

- Escalate Gemini Nano background restrictions through cloud tiers already permitted by
  the user.
- Preserve secure-window and screenshot-capture failure states.

## [0.5.0] - 2026-09-14

### Fixed

- Recover text entry in rich Android editors through focused paste fallback.
- Escalate failed accessibility actions to visual grounding.
- Verify visually grounded typing after the text action, not before it.
- Require renewable, expiring ChatGPT subscription sessions.
- Keep OAuth loopback listeners alive across unrelated local requests.

[0.5.0]: https://github.com/autobile/autobile/releases/tag/v0.5.0
[0.6.0]: https://github.com/autobile/autobile/releases/tag/v0.6.0
[0.6.1]: https://github.com/autobile/autobile/releases/tag/v0.6.1
