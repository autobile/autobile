# Changelog

All notable changes to Autobile are documented here. Releases follow semantic versioning.

## [0.9.2] - 2026-09-16

### Performance

- Stop waiting up to the full semantic-validation timeout for an accessibility tree to
  become stable after every visual-game action. Recheck package identity once after a
  short settle period; the next turn's fresh screenshot remains authoritative.
- Bound retained visual-action history in memory while preserving the existing
  12-action model context window.

### Fixed

- Keep two-observation completion verification without adding an accessibility
  stability wait that animated games may never satisfy.

## [0.9.1] - 2026-09-16

### Fixed

- Replace the unreachable "suspended until review" state with an actionable explanation
  that points to the automation's autonomy controls.
- Cap degraded automations at attended `Ask first` execution instead of deadlocking them
  in `Watch only`, so successful supervised runs can restore confidence.
- Make `Ask first` actually request confirmation for low-risk actions instead of
  silently allowing them.
- Explain `Watch only` and confidence-based autonomy limits directly on the automation
  detail screen.

## [0.9.0] - 2026-09-16

### Added

- Classify demonstrations as fixed replays or dynamic visual-agent tasks. Games,
  puzzles, canvases, and changing boards are compiled directly to a screenshot-driven
  objective instead of replaying the demonstrated taps.
- Allow plain-language corrections to propose bounded step insert, replace, and delete
  operations through a closed executable action vocabulary.
- Give long visual tasks up to 256 freshly observed actions, enough for multi-move
  puzzles whose solution was not fully demonstrated.

### Changed

- Treat a demonstration as evidence of the goal rather than the sequence an autonomous
  visual task must copy.
- Preserve recent gesture coordinates and outcomes in the bounded visual-agent context
  to reduce repeated non-progress actions.

### Fixed

- Make both the post-demonstration correction field and saved-automation editor capable
  of changing executable step structure instead of rejecting every unrecognized
  behavior change.
- Collapse all dynamic in-app demonstration actions into one visual task while retaining
  deterministic launch and an explicitly requested post-completion exit.

## [0.8.0] - 2026-09-15

### Added

- Add a first-class visual-task action for games and canvas interfaces. It observes a
  fresh screenshot for every turn and can choose a tap, long press, exact-point swipe,
  wait, completion, or blocked result.
- Require two fresh visual completion observations before a game task can advance to a
  recorded exit action.

### Changed

- Interpret behavior edits through a structured, language-independent AI plan instead
  of matching hard-coded Korean or English phrases.
- Select exit steps by stable step IDs supplied by the edit plan, with structural Home
  and Back actions used only as a compatibility fallback.
- Use different image-capable ChatGPT catalog fallbacks for light and advanced tiers so
  one unsupported model does not make both cloud attempts identical.

### Fixed

- Stop treating a repeated click with a high retry count as autonomous game play.
- Keep visual tasks inside the learned app and reject system-edge gestures, missing
  screenshot runtimes, false progress, and unconfirmed completion.

## [0.7.3] - 2026-09-15

### Fixed

- Compile plain-language requests to finish a game before exiting into a bounded,
  screenshot-grounded gameplay loop followed by the original exit action.
- Require an unambiguous victory, clear, results, or completion state before the
  gameplay loop can pass; ordinary visual progress no longer permits an early exit.
- Re-ground progressing game actions from each fresh frame without permanently
  excluding a coordinate that may remain valid after the board changes.
- Allow an explicit Home step to validate its intended launcher transition instead of
  inheriting the preceding game's foreground-package requirement.

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
