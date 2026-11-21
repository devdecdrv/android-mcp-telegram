# Android MCP + Telegram Bot

Android remote control MCP server with automatic Cloudflare tunnel URL delivery via Telegram bot.

---

## Setup (पहले यह करें)

### Step 1 — Credentials add करें

`local.properties` खोलें (यह file git में नहीं जाती, .gitignore में है):

```properties
TELEGRAM_BOT_TOKEN=1234567890:ABCdef...   ← @BotFather से मिला token
TELEGRAM_CHAT_ID=123456789               ← @userinfobot से मिला ID
```

ये credentials APK के string resources में नहीं होते — bytecode में inject होते हैं।
Release build में R8 obfuscation इन्हें और obscure कर देता है।

### Step 2 — Cloudflare binary add करें

https://github.com/cloudflare/cloudflared/releases से ARM64 binary download करें:

```
app/src/main/assets/cloudflared-arm64    ← ARM64 devices (most modern phones)
app/src/main/assets/cloudflared-x86_64   ← x86_64 (emulators)
```

### Step 3 — Build करें

```bash
./gradlew assembleRelease
```

APK location: `app/build/outputs/apk/release/app-release.apk`

### Step 4 — Phone पर install करें

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

### Step 5 — Phone पर setup

1. App खोलें
2. **"Open Accessibility Settings"** tap करें → Android MCP Control enable करें
3. **"Start Server"** tap करें
4. कुछ seconds में Telegram bot आपको message करेगा:
   ```
   🤖 Android MCP Server Started
   Tunnel URL: https://xxxx.trycloudflare.com/mcp
   ```

### Step 6 — Claude.ai में add करें

1. claude.ai → Customize → Connectors → Add custom connector
2. URL paste करें: `https://xxxx.trycloudflare.com/mcp`
3. OAuth/Client fields खाली छोड़ें
4. Save करें — Claude अब आपका phone control कर सकता है

---

## Tools

| Tool | Description |
|------|-------------|
| `android_get_screen_state` | Current UI state (TSV + optional screenshot) |
| `android_press_back` | Back button |
| `android_press_home` | Home button |
| `android_press_recents` | Recent apps |
| `android_open_notifications` | Notification shade |
| `android_open_quick_settings` | Quick settings |
| `android_dismiss_keyboard` | Dismiss keyboard |
| `android_power_button` | **Power/lock screen** (GLOBAL_ACTION_LOCK_SCREEN) |
| `android_tap` | Tap at x,y |
| `android_long_press` | Long press |
| `android_double_tap` | Double tap |
| `android_swipe` | Swipe gesture |
| `android_scroll` | Scroll up/down/left/right |
| `android_find_nodes` | Find UI nodes by text/id |
| `android_click_node` | Click node by ID |
| `android_long_click_node` | Long click node |
| `android_tap_node` | Gesture tap on node |
| `android_scroll_to_node` | Scroll node into view |
| `android_get_clipboard` | Read clipboard |
| `android_set_clipboard` | Set clipboard |
| `android_open_app` | Launch app by package |
| `android_list_apps` | List installed apps |
| `android_close_app` | Kill background app |
| `android_open_uri` | Open URL/deep link |

---

## Security Notes

- **Credentials** `local.properties` में हैं — git में कभी push नहीं होते (.gitignore देखें)
- BuildConfig में inject होते हैं → APK bytecode में, string resources table में नहीं
- Release build में R8 obfuscation automatically apply होता है
- Tunnel URL randomly generated होता है, हर start पर बदलता है
- Claude.ai connection OAuth handshake से होता है (Cloudflare tunnel = HTTPS only)

---

## Power Button Note

`android_power_button` tool uses `GLOBAL_ACTION_LOCK_SCREEN` (Android API 28+).
यह screen को lock करता है — physical power button press जैसा effect।
Root के बिना actual hardware power key inject नहीं हो सकती Android पर।
