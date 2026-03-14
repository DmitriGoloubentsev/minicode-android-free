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

## Architecture

- MVVM with Hilt DI, Kotlin coroutines, StateFlow
- Custom TerminalEmulator (VT100/xterm-256color) with TerminalBuffer
- Apache MINA SSHD for SSH/SFTP
- sora-editor (Rosemoe) 0.23.6 with TextMate grammars for syntax highlighting
- Foldable support via `configChanges` handling (no activity recreation on fold/unfold)

## License

Proprietary
