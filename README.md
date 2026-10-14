# MagPaper (Offline Passive E-Ink NFC Driver)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![100% Offline](https://img.shields.io/badge/Privacy-100%25%20Offline-blue.svg)](#privacy--security)

An open-source, 100% offline, privacy-first Android driver for writing custom images to passive **E-Ink NFC Fridge Magnets** and compatible Shanghai Fudan `FM1280` ePaper displays (such as VidaBay Snap models `SN3H-G-31-V100`, `SN3H-W`, and `SN60`).

Unlike proprietary OEM apps, **MagPaper requires zero cloud accounts, sends zero tracking telemetry**, and runs completely offline using pure Android `IsoDep` NFC.

---

## ✨ Features

- **100% Offline & Private:** Zero internet permissions (`android.permission.INTERNET` is not even requested in manifest). No logins, no tracking, no image uploads to remote servers.
- **Hardware-Accurate 4-Color Dithering:** Floyd-Steinberg error diffusion quantized for Black, White, Red, and Yellow e-paper particles.
- **Paced Energy-Harvesting NFC Engine:** The E-Ink magnet has no battery; it harvests energy from your phone's NFC radio. MagPaper features configurable pacing and soaking delays to prevent capacitor brownouts on devices with smaller NFC coils (e.g. Google Pixel series, foldables).
- **Recent Image History:** Quickly cycle or re-transfer previously processed images stored locally on device.
- **Live APDU / Hex Diagnostics Console:** Full real-time insight into ISO 7816-4 APDU commands, chunk checksums, and refresh waveforms.
- **Instant Tap Retry:** Failed a flip due to holding angle? Tap **Retry** and re-touch immediately without having to re-select images or clear app data.

---

## 📱 Hardware Compatibility

Tested and verified on:
- **Model:** `SN3H-G-31-V100` (and `SN3H-W`, `SN60`)
- **Controller IC:** Shanghai Fudan Microelectronics `FM1280` NFC-to-ePaper bridge (`0x1D` UID vendor prefix)
- **Display Resolution:** $400 \times 400$ pixels (40,000 bytes raw framebuffer, 4-color column-major packing)
- **Legacy Compatibility:** Supports fallback 240x240 geometry ($14,400$ bytes).

---

## ⚡ Transfer & Alignment Guide (The "Angle" Factor)

The E-Ink magnet has **no internal battery**. While transferring data only takes ~3-5 mA, flipping the microcapsules (the physical screen refresh) takes **~20-25 mA for 20-25 seconds** from the magnet's passive capacitor bank.

### Pro-Tips for 100% Reliable Writes:
1. **Remove thick phone cases:**
   - Heavy leather, wallet, credit-card holders, or kickstand cases severely reduce inductive energy transfer. If you experience connection dropouts or "APDU response too short", remove the case.
2. **Find your phone's NFC antenna sweet spot:**
   - **Google Pixel:** Upper edge near the camera visor bar.
   - **Samsung Galaxy / Motorola:** Middle back or slightly above center.
   - **iPhones / others:** Top edge.
3. **Hold completely flat & still:**
   - Rest the phone directly against the magnet.
   - Keep contact steady for ~30 seconds (10s data transfer + 20s screen refresh).
4. **If data reaches 100% but the screen does not flip:**
   - The magnet's capacitor dropped voltage during the high-power waveform trigger.
   - **Adjust the contact angle by 10-15°** or shift by 1-2 cm to achieve maximum magnetic flux coupling between the coils.
   - Tap **Retry** and hold in the new position.

---

## ⚙️ Advanced Connection Tuning (Sliders)

Under **Advanced Energy & Pacing Tuning** in the app:
- **Inter-Packet Delay (Default: 35 ms):** Inserts small micro-pauses between 180-byte APDU chunk packets to allow the magnet's capacitor to top off. Increase to 50 ms for weak phone coils.
- **Pre-Charge Soak Delay (Default: 600 ms):** Settling time after initial NFC handshake before transmitting data.
- **Pre-Refresh Charge Pause (Default: 1500 ms):** Critical pause after the last chunk is written before issuing the `00 4A 05 00` screen refresh command. Allows the high-voltage charge pump to fully saturate.

---

## 🛠️ Building from Source

### Prerequisites:
- Android Studio Ladybug / Meerkat or later
- JDK 17 or JDK 21 (included in Android Studio JBR: `C:\Program Files\Android\Android Studio\jbr`)
- Android SDK Platform 35 / 36

### Build Steps:
```bash
git clone https://github.com/yourusername/SnapInk.git
cd SnapInk
./gradlew assembleDebug
```
The compiled APK will be at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 🔒 Privacy & Security

| Feature | Official App | SnapInk |
| :--- | :--- | :--- |
| **Internet Access** | ✅ Uploads to remote servers (`kaiconn.com`) | ❌ **No Internet permission** |
| **Account Required** | ✅ Phone number / WeChat / Email | ❌ **100% Offline** |
| **Telemetry / Tracking** | ✅ Logs device ID and image uploads | ❌ **Zero tracking** |
| **Open Source** | ❌ Proprietary | ✅ **MIT Licensed** |

---

## ⚖️ Legal & Trademarks Disclaimer

- This is an independent, clean-room open-source project created strictly for hardware interoperability and fair-use educational purposes under [17 U.S.C. § 1201(f)](https://www.law.cornell.edu/uscode/text/17/1201).
- **VidaBay** and all related names, marks, emblems, and images are registered trademarks of their respective owners.
- This project is **not** affiliated with, endorsed by, or sponsored by VidaBay or Shanghai Fudan Microelectronics.
