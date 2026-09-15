# Changelog

All notable changes to Autobile are documented here. Releases follow semantic versioning.

## [0.7.2] - 2026-09-15

### Fixed

- Apply supported plain-language behavior corrections to executable steps instead of
  changing only descriptive text, and reject edits that cannot safely change behavior.
- Show the active head version immediately after a direct edit or accepted repair.
- Refresh the selected automation and version history in the same state update that
  commits a direct edit or accepted recovery proposal.
- Reject visual targets inside system status/navigation edges, including the Android
  Home control shown over full-screen games.
- Tell visual grounding not to select Home, Back, Recents, or app-exit controls unless
  the requested action explicitly names them.
- Require semantic and visual validation to remain in the declared app before accepting
  a step or persisting a recovery.
- Relaunch the intended app before retrying a recovery that unexpectedly left it.

## [0.7.1] - 2026-09-15

### Fixed

- Continue an image-based recovery through an explicitly enabled cloud vision tier when
  the on-device runtime reports a generic policy restriction while another app is in
  front.
- Report screenshot consent as a policy requirement instead of claiming the selected
  cloud model lacks vision.
- Select an image-capable ChatGPT subscription model from account catalog metadata and
  fail closed when every advertised model is text-only.
- Show screen understanding as available only when screenshot capture and an actual
  vision runtime are both available.
- Clear screenshot-upload consent when cloud access is disabled so Settings cannot show
  an enabled but ineffective combination.

### Added

- Record every visual fallback's fresh screenshot capture and dimensions in execution
  history without retaining or logging image content.

## [0.7.0] - 2026-09-15

### Added

- Let users choose on-device-first or cloud-first inference while deterministic actions
  remain the first execution tier.
- Guide devices without on-device AI through ChatGPT Subscription setup during
  onboarding, including explicit screenshot consent for visual recovery.
- Re-ground failed visual actions from fresh screenshots and exclude points that have
  already failed during the current step.

### Fixed

- Recognize renewable ChatGPT Subscription sessions as configured cloud capability
  instead of requiring an unrelated API key.
- Defer rootless visual steps when no vision runtime is available instead of reporting
  that the visible control does not exist.
- Reject false visual success caused by animation away from the grounded control.
- Keep transient screenshot and accessibility errors recoverable instead of labeling
  every unknown platform capture failure as a secure window.

## [0.6.4] - 2026-09-15

### Fixed

- Prevent Android 11's ICU regex engine from crashing the process while the runtime
  initializes temporal input editing.
- Install and launch the minified release APK on an API 30 emulator in every required
  verification run.

### Changed

- Remove redundant release-unit-test variants from pull-request CI and reuse the
  already assembled minified APK for the startup gate.
- Enable hardware acceleration for the startup emulator and avoid rerunning the full
  verification suite during signed artifact publication.
- Boot the startup emulator concurrently with the minified build instead of adding its
  setup time after R8 completes.

## [0.6.3] - 2026-09-15

### Fixed

- Render temporal literals in automations saved by earlier versions against the current
  run time, without requiring the user to recreate or edit the automation.

## [0.6.2] - 2026-09-15

### Fixed

- Compile a date or timestamp embedded in a larger text entry as a runtime value instead
  of replaying the characters captured during teaching.
- Apply current-date and current-time corrections to input steps from both the initial
  review and the saved automation editor.
- Prevent a timestamp mentioned in a current-time correction from being mistaken for a
  schedule change.

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
[0.6.2]: https://github.com/autobile/autobile/releases/tag/v0.6.2
[0.6.3]: https://github.com/autobile/autobile/releases/tag/v0.6.3
[0.6.4]: https://github.com/autobile/autobile/releases/tag/v0.6.4
[0.7.0]: https://github.com/autobile/autobile/releases/tag/v0.7.0
