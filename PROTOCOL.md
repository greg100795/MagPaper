# VidaBay Snap E-Ink NFC Magnet: Protocol & Image Format Specification

## 1. Hardware Overview & Target Fingerprint
- **Tag Type:** ISO/IEC 14443-4 (`IsoDep`, `NfcA`)
- **ATQA:** `0x0044`
- **SAK:** `0x20`
- **IC Vendor:** Shanghai Fudan Microelectronics (`0x1D` prefix in UID, `FM1280` NFC-to-ePaper energy-harvesting bridge)
- **Target Magnet Model:** `SN3H-G-31-V100`
- **E-Ink Display Native Resolutions:**
  - **400 × 400 pixels** (Models: `SN3H-G`, `SN3H-W`, `SN60`) -> **40,000 bytes** raw buffer
  - **240 × 240 pixels** (Legacy / compact models) -> **14,400 bytes** raw buffer
- **Colors:** 4-Color (Black, White, Red, Yellow)
- **Default Auth Key:** `3A 60 43 2A 5C 01 21 1F 29 1E 0F 4E 0C 13 28 25` (16 bytes hex, expanded to 32 bytes by duplication)

---

## 2. APDU Transport Layer (`IsoDep`)

All custom controller commands are encapsulated in an ISO 7816-4 APDU frame:
```
CLA  INS  P1   P2   Lc      Data...
0x00 0x4A 0x00 0x00 [Len]   [Command Payload]
```
When `isoDep.transceive(apdu)` is called, the device prepends a 2-byte header (`50 20`), so raw response payloads are read starting from byte offset 2.

### Step-by-Step Communication Flow

### Step 1: Tag Probe / Wakeup
- **Send:** `00 4A 00 00 20` + 32-byte probe payload:
  `00 00 00 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00`
- **Response:** 32-byte tag status descriptor.
  - Byte 2: `0x26`, Byte 3: `0x52` (device family)
  - Byte 5: `0x00` = AES / standard mode; `0x01` = SM4 mode.

### Step 2: Query Device Version & Hardware Dimensions
- **Send:** `00 4A 00 00 20` + 32-byte query payload:
  `00 4A 01 00` followed by 28 zero bytes.
- **Response:** 34-byte device descriptor (stripped 2-byte prefix):
  - Byte 4: Model flag byte (`0x02` for `SN3H-G`)
  - Byte 6..19: Hardware model string (e.g. `SN3H-G-31-V100` in ASCII)
  - Byte 20: Display model code
  - Byte 21..22 (Big Endian): Display Width = `0x0190` (400 px) or `0x00F0` (240 px)
  - Byte 23..24 (Big Endian): Display Height = `0x0190` (400 px) or `0x00F0` (240 px)
  - Trailing 4 bytes (indices 30..33): Hardware Session Token / Lock ID tail (`token0..token3`)

### Step 3: Auth / Session Arming
- **Send:** `00 4A 00 00 20` + 32-byte auth packet:
  - Header: `00 4A 09 00 00 FF` (6 bytes)
  - Padding: 22 zero bytes
  - Token: 4 bytes from Step 2 trailing bytes (`token0..token3`)
- **Response:** `90 00` success.

### Step 4: Initiate Block Transfer
- Total block calculation:
  - For $400 \times 400$: `ceil(40000 / 180) = 223` chunks.
  - For $240 \times 240$: `ceil(14400 / 180) = 80` chunks.
- **Send:** `00 4A 00 00 20` + 32-byte init transfer payload:
  - Header: `00 4A 09 01 02` (5 bytes)
  - Total chunks (BE 16-bit): `(totalBlocks >> 8), (totalBlocks & 0xFF)` (e.g. `0x00 0xDF` for 223)
  - Padding: 21 zero bytes
  - Token: 4 bytes from Step 2 (`token0..token3`)
- **Response:** Bytes 6..7 contain the first chunk index requested by the tag (index `1`).

### Step 5: Send Image Data Blocks
- Image payload is divided into 180-byte slices:
  - For the final slice (e.g. chunk 223 with 40 remaining bytes), pad with `0xFF` to 180 bytes.
  - Append 1-byte 8-bit checksum: `sum(slice[0..179]) & 0xFF`.
  - Slice size with checksum: **181 bytes**.
- Chunk Packet Assembly:
  - Prefix: `00 4A 09 02` (4 bytes)
  - Chunk index (BE 16-bit): `(chunkIdx >> 8), (chunkIdx & 0xFF)` (2 bytes, 1-indexed)
  - Data: 181 bytes (180 bytes pixel data + 1 byte checksum)
  - Suffix: `0x00` (1 byte)
  - Total chunk packet length = 4 + 2 + 181 + 1 = **188 bytes**.
- Wrap in APDU:
  - APDU: `00 4A 00 00 BC` (where `0xBC` = 188) + [188-byte chunk packet].
- Send via `isoDep.transceive(apdu)`.
- **Termination Dummy Block:**
  - For models with flag `0x01` or `0x02` (such as `SN3H-G`), send an extra termination block `totalBlocks + 1` (e.g. `224`):
    `00 4A 09 02 [blockNum 2 bytes] [180 zeroes] [00 sum] [00 suffix]` (188 bytes total).

### Step 6: Trigger E-Ink Screen Refresh & Polling
After all data chunks are written:
- Soak pause (default 800ms) to allow passive capacitor bank to reach full charge.
- Prepare 32-byte refresh trigger command:
  - Header: `00 4A 05 00` (4 bytes)
  - Padding: 24 zero bytes (indices 4..27)
  - Token: 4 bytes (`token0..token3` at indices 28..31)
- Wrap in APDU: `00 4A 00 00 20` + [32-byte refresh trigger].
- **Polling Loop (Energy Delivery):**
  - Loop up to 80 times every 150ms calling `isoDep.transceive(refreshApdu)`.
  - While the physical E-Ink microcapsules move, transceive supplies continuous RF energy to keep the capacitor bank charged.
  - When `response.length > 5 && response[2] == 0x09 && response[3] == 0x03 && response[5] == 0x00`, the refresh is fully complete!

---

## 3. Image Format & Pixel Packing Specification

### Geometry & Transforms
1. **$400 \times 400$ Displays (`SN3H-G`):**
   - Resized/cropped to **400 × 400 pixels**.
   - **NO Horizontal Flip** (mirroring was only used on legacy 240 displays).
   - **Rotation:** Rotated **270° clockwise** (or 90° counter-clockwise) to align with hardware panel scan lines.
2. **$240 \times 240$ Displays:**
   - Resized/cropped to 240 × 240 pixels.
   - Horizontal Flip + 270° clockwise rotation.

### 4-Color Palette Quantization
| Color | Hex RGB | Binary Code | Integer Code |
| :--- | :--- | :--- | :--- |
| **Black** | `#000000` | `00` | `0` |
| **White** | `#FFFFFF` | `01` | `1` |
| **Yellow** | `#FFFF00` | `10` | `2` |
| **Red** | `#FF0000` | `11` | `3` |

### Color Threshold Logic:
- If `max(R, G, B) < 128` -> **Black** (`00`)
- Else if `R > 128 && B < 128 && G < 128` -> **Red** (`11`)
- Else if `R > 128 && G > 128 && B < 128` -> **Yellow** (`10`)
- Else -> **White** (`01`)

Floyd-Steinberg error diffusion dithering is applied across the 4 palette colors for photographic gradients.

### Column-Major Byte Packing
- Order is **Column-Major**: Scan x from 0 to width - 1. Within each column, scan y from 0 to height - 1.
- 4 vertical pixels are packed into 1 byte:
  ```
  Byte = (pixel[y+0] << 6) | (pixel[y+1] << 4) | (pixel[y+2] << 2) | (pixel[y+3] << 0)
  ```
- For 400x400:
  - 400 pixels / 4 = **100 bytes per column**.
  - 400 columns $\times$ 100 bytes = **40,000 bytes** total payload.
- For 240x240:
  - 240 pixels / 4 = **60 bytes per column**.
  - 240 columns $\times$ 60 bytes = **14,400 bytes** total payload.

---

## 4. Why the Previous Attempt Failed & How It Is Resolved

1. **Resolution Mismatch (Garbled Image):**
   - The initial version sent $14,400$ bytes (240x240 geometry) to a $400 \times 400$ frame buffer.
   - The updated app now generates full $400 \times 400$ ($40,000$ bytes across 223 chunks) without the erroneous horizontal flip.
2. **Screen Refresh Dropout (`TagLostException`):**
   - The hardware controller requires all 223 chunks PLUS the protocol termination dummy block (`chunk 224`) before initiating the waveform.
   - During the refresh cycle, `VidaBayPaced` now runs an active polling loop every 150ms, keeping the phone's NFC RF carrier powered and feeding the capacitor until the display confirms completion.
