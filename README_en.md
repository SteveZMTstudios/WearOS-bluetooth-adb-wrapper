
# WearOS Bluetooth Debug Bridge

[简体中文](README.md) | English | [Русский](README_ru.md)

A foreground service app installed on your phone to restore the missing phone-side Bluetooth ADB bridge capability for WearOS 3.

On the watch side, keep using the system setting "Debug over Bluetooth". This app is responsible for the phone side:

- Select a paired watch
- Start RFCOMM Bluetooth handshake
- Expose local `adb-hub`
- Optionally open a TCP port for local or LAN `adb connect`

> [!WARNING]
> Bluetooth debugging was fully removed in versions after WearOS 3.
> This tool only works on WearOS versions between 2 and 3. 🤦
> If your watch Developer Options does not contain "Debug over Bluetooth", your system version is not supported.

## Current Features

- List paired Bluetooth devices on the phone and select the target watch
- Request "Nearby Devices / Bluetooth Connect / Notifications" permissions on cold start
- Keep bridge logic alive in a foreground service
- Optional TCP listening port, default `44444`, manually configurable to `1-65535`
- Separate status lines for service, host, and target with color indicators
- "Endpoint Info" and "Connection Commands" are collapsed by default and can be expanded
- Supports tap-to-copy and copy button for endpoint info and connection commands
- Handshake failures, port conflicts, missing permissions, and other errors are shown in UI and notification

## Requirements

- Android phone
- Wear OS watch already paired with the phone over Bluetooth
- "Debug over Bluetooth" enabled in watch Developer Options

To build this app:
- JDK 11 or above
- A working Gradle build environment

## Build

Windows:

```powershell
.\gradlew.bat assembleDebug
```

macOS / Linux:

```bash
./gradlew assembleDebug
```

Debug APK output path:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Prebuilt APKs are available on the [Releases page](https://github.com/SteveZMTstudios/WearOS-bluetooth-adb-wrapper/releases/).

## Usage

1. Install and launch the app on your phone.
2. Grant required permissions on first cold start.
3. Select your target watch on the main screen.
4. If TCP is needed, enable the switch and enter a listening port.
5. Enable "Debug over Bluetooth" in watch system settings.
6. Tap "Start Bridge".
7. Observe the status rows: "Service / Host / Target".
8. Expand "Connection Commands" when needed, then tap content or copy button.

## Two Connection Methods

### 1. Classic `adb-hub`

The phone creates a local abstract socket:

```text
localabstract:/adb-hub
```

After your computer is connected to the phone (USB or wireless), run:

```bash
adb forward tcp:44444 localabstract:/adb-hub
adb connect 127.0.0.1:44444
```

If you changed the TCP port, replace `44444` accordingly.

### 2. TCP Listening

After enabling "Enable TCP Listening", the app listens on the specified phone port and shows the currently available address in "Endpoint Info".

With this, tools on the phone (for example Termux) can connect to the watch via IP address without requiring Wi-Fi on the phone, and other devices in the same LAN can also connect for debugging.

Example:

```bash
adb connect 192.168.1.23:44444
```

## License

MIT License
