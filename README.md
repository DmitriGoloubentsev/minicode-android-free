# MiniCode

Native Android SSH terminal and code editor, built for Samsung foldable devices.

## Features

- **SSH Terminal** — Full VT100/xterm-256color terminal emulator via Apache MINA SSHD
- **Code Editor** — Syntax highlighting for 20+ languages using sora-editor with TextMate grammars
- **SFTP File Tree** — Browse, create, rename, and delete remote files
- **Split Layout** — Side-by-side file tree, editor, and terminal on foldable inner display (600dp+)
- **Voice Input** — Hold mic button to dictate commands; auto-restarts on timeout for continuous recording
- **Bell Notifications** — Terminal BEL character triggers vibration and/or chime (configurable)
- **Floating Toolbar** — Draggable Esc/Backspace/Enter/Mic buttons, adjusts for keyboard
- **Keyboard Toolbar** — Optional Ctrl/Alt/Tab/Esc/arrow keys bar (toggle via long-press Esc)
- **Fold/Unfold** — Seamless transition between cover and inner display without losing session

## Build

```bash
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/opt/dimach/Android/Sdk ./gradlew assembleDebug
```

Install on device:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Bell Notifications

MiniCode's terminal responds to the BEL character (`\a` / `0x07`) with vibration and/or chime. Toggle the mode via the bell icon in the terminal header bar (cycles: off / vibrate / vibrate+chime).

### With Claude Code

To get notified when Claude Code finishes a task:

```bash
printf '\a' > /proc/$PPID/fd/1
```

Or configure Claude Code to use built-in terminal bell on the remote server (`~/.claude/settings.json`):

```json
{
  "preferredNotifChannel": "terminal_bell"
}
```

### Notes
- Chime uses the ALARM audio stream, so it plays even on silent mode
- Bells are debounced (500ms) to prevent rapid-fire notifications
- The setting persists across sessions

## Releases

### v1.3.0 (build 13)

- Initial F-Droid and Google Play release
- Skip update checker in FOSS flavor for F-Droid compliance
- Full terminal emulator with xterm-256color support
- Code editor with syntax highlighting for 30+ languages
- SFTP file browser with create, rename, delete
- Split-panel layout for foldable devices and tablets
- Multi-session SSH with tabbed interface
- Voice input support (Google Speech + offline Parakeet in Pro)
- Persistent SSH connections via foreground service

### Downloads

| Flavor | Download |
|--------|----------|
| Pro (Sherpa-ONNX, 30 MB) | [minicode-play-1.3.0.apk](https://minicode.app/minicode/releases/minicode-play-1.3.0.apk) |
| Free (12 MB) | [minicode-playFree-1.3.0.apk](https://minicode.app/minicode/releases/minicode-playFree-1.3.0.apk) |
| FOSS (12 MB) | [minicode-foss-1.3.0.apk](https://minicode.app/minicode/releases/minicode-foss-1.3.0.apk) |

## Google Play Submission

1. Go to [Google Play Console](https://play.google.com/console)
2. Create two app listings:
   - **MiniCode** (`com.minicode`) — Paid $8.99
   - **MiniCode Free** (`com.minicode.free`) — Free
3. Fill in store listing (use content from `fastlane/metadata/android/en-US/`)
4. Complete content rating questionnaire
5. Set privacy policy URL: `https://minicode.app/privacy`
6. Go to **Release** → **Production** → **Create new release**
7. Upload signed APKs:
   - `minicode-play-1.3.0.apk` for Pro
   - `minicode-playFree-1.3.0.apk` for Free
8. Add release notes and roll out

### Building signed APKs

```bash
# Build
./gradlew assemblePlayRelease assemblePlayFreeRelease

# Sign
APKSIGNER=$ANDROID_HOME/build-tools/35.0.0/apksigner
ZIPALIGN=$ANDROID_HOME/build-tools/35.0.0/zipalign

$ZIPALIGN -v -p 4 app/build/outputs/apk/play/release/app-play-release-unsigned.apk minicode-play.apk
$APKSIGNER sign --ks minicode-release.jks --ks-key-alias minicode minicode-play.apk

$ZIPALIGN -v -p 4 app/build/outputs/apk/playFree/release/app-playFree-release-unsigned.apk minicode-playFree.apk
$APKSIGNER sign --ks minicode-release.jks --ks-key-alias minicode minicode-playFree.apk
```

## Architecture

- MVVM with Hilt DI, Kotlin coroutines, StateFlow
- Custom TerminalEmulator (VT100/xterm-256color) with TerminalBuffer
- Apache MINA SSHD for SSH/SFTP
- sora-editor (Rosemoe) 0.23.6 with TextMate grammars for syntax highlighting
- Foldable support via `configChanges` handling (no activity recreation on fold/unfold)

## License

AGPL-3.0 — see [LICENSE](LICENSE)
