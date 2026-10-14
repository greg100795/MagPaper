package com.vidabay.paced.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

sealed class NfcTransferState {
    object Idle : NfcTransferState()
    object ArmedWaitingForTag : NfcTransferState()
    data class Connected(val tagUid: String) : NfcTransferState()
    object PreCharging : NfcTransferState()
    object Handshaking : NfcTransferState()
    data class Transferring(val currentChunk: Int, val totalChunks: Int, val progress: Float) : NfcTransferState()
    object PreRefreshSoak : NfcTransferState()
    object RefreshingScreen : NfcTransferState()
    object PollingStatus : NfcTransferState()
    data class Success(val message: String) : NfcTransferState()
    data class Error(val error: String) : NfcTransferState()
}

data class LogEntry(
    val timestamp: String,
    val isTx: Boolean,
    val message: String,
    val hexData: String? = null
)

class NfcEngine(private val activity: Activity) {

    private val TAG = "VidaBayNfcEngine"
    private var nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    private val _transferState = MutableStateFlow<NfcTransferState>(NfcTransferState.Idle)
    val transferState = _transferState.asStateFlow()

    private val _logFlow = MutableSharedFlow<LogEntry>(replay = 100)
    val logFlow = _logFlow.asSharedFlow()

    var preChargeSoakDelayMs: Long = 600L
    var interPacketDelayMs: Long = 35L
    var preRefreshDelayMs: Long = 2000L

    private var armedPayload: ByteArray? = null
    private var isTransferring = false

    val isNfcSupported: Boolean
        get() = nfcAdapter != null

    val isNfcEnabled: Boolean
        get() = nfcAdapter?.isEnabled == true

    fun armForTransfer(payload: ByteArray) {
        if (payload.size != 40000 && payload.size != 14400) {
            log(false, "Unexpected payload size: ${payload.size} (expected 40,000 for 400x400 or 14,400 for 240x240)")
        }
        disarm() // Always do a clean teardown of previous reader session first
        armedPayload = payload
        isTransferring = false
        enableReaderMode()
        _transferState.value = NfcTransferState.ArmedWaitingForTag
        log(false, "NFC Engine armed for transfer (${payload.size} bytes). Hold phone against the VidaBay Snap magnet.")
    }

    fun disarm() {
        disableReaderMode()
        isTransferring = false
        _transferState.value = NfcTransferState.Idle
        log(false, "NFC Engine disarmed.")
    }

    fun retry() {
        val payload = armedPayload
        if (payload != null) {
            log(false, "Resetting NFC session for retry...")
            armForTransfer(payload)
        } else {
            log(false, "No previous payload available for retry.")
        }
    }

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        val extras = Bundle().apply {
            // Keep RF field engaged and skip OS tag presence drops during high-draw refresh
            // Official SDK uses 40,000ms timeout
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 40000)
        }

        adapter.enableReaderMode(
            activity,
            { tag -> onTagDiscovered(tag) },
            flags,
            extras
        )
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(activity)
    }

    private fun onTagDiscovered(tag: Tag) {
        if (isTransferring) {
            log(false, "Tag detected, but transfer is already in progress.")
            return
        }

        val payload = armedPayload
        if (payload == null) {
            log(false, "Tag detected, but no payload is armed.")
            return
        }

        val tagUid = bytesToHex(tag.id)
        _transferState.value = NfcTransferState.Connected(tagUid)
        log(false, "Tag connected! UID: $tagUid. Techs: ${tag.techList.joinToString()}")

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            log(false, "Tag does not support ISO 14443-4 (IsoDep).")
            _transferState.value = NfcTransferState.Error("Tag does not support IsoDep")
            return
        }

        isTransferring = true
        thread(name = "VidaBayNfcThread") {
            executeTransfer(isoDep, payload)
        }
    }

    private fun executeTransfer(isoDep: IsoDep, imageBytes: ByteArray) {
        try {
            isoDep.connect()
            isoDep.timeout = 20000 // 20-second timeout

            // 1. Pre-Charge Soak Delay (bank energy in magnet's capacitor)
            _transferState.value = NfcTransferState.PreCharging
            log(false, "Soaking passive capacitor bank for ${preChargeSoakDelayMs}ms...")
            Thread.sleep(preChargeSoakDelayMs)

            // 2. Handshake Probe
            _transferState.value = NfcTransferState.Handshaking
            log(false, "Sending tag probe...")
            val probeCmd = ByteArray(32).apply { this[4] = 0x01 }
            val probeResp = sendCustom(isoDep, probeCmd)
            log(false, "Tag probe response: ${bytesToHex(probeResp)}")

            // 3. Query Device Info & Screen Dimensions
            log(false, "Querying device info & screen dimensions...")
            val queryCmd = ByteArray(32).apply {
                this[0] = 0x00
                this[1] = 0x4A
                this[2] = 0x01
                this[3] = 0x00
            }
            val infoResp = sendCustom(isoDep, queryCmd)
            if (infoResp.size < 32) {
                throw IOException("Invalid device info response length: ${infoResp.size}")
            }

            val width = ((infoResp[21].toInt() and 0xFF) shl 8) or (infoResp[22].toInt() and 0xFF)
            val height = ((infoResp[23].toInt() and 0xFF) shl 8) or (infoResp[24].toInt() and 0xFF)
            val flagByte = infoResp[4].toInt() and 0xFF
            log(false, "Detected Hardware: Display ${width}x${height} px (Flag: 0x${flagByte.toString(16)})")

            // Session Token / Lock ID tail (last 4 bytes of info response)
            val sessionToken = infoResp.copyOfRange(infoResp.size - 4, infoResp.size)
            log(false, "Hardware Session Token: ${bytesToHex(sessionToken)}")

            // 4. Session Arming
            log(false, "Arming session...")
            val authCmd = ByteArray(32).apply {
                this[0] = 0x00
                this[1] = 0x4A
                this[2] = 0x09
                this[3] = 0x00
                this[4] = 0x00
                this[5] = 0xFF.toByte()
                System.arraycopy(sessionToken, 0, this, 28, 4)
            }
            val authResp = sendCustom(isoDep, authCmd)
            log(false, "Session armed: ${bytesToHex(authResp)}")

            // 5. Initialize Block Transfer
            // 40,000 bytes / 180 = 222 r 40 -> 223 blocks
            // 14,400 bytes / 180 = 80 blocks
            val totalBlocks = Math.ceil(imageBytes.size.toDouble() / 180.0).toInt()
            log(false, "Initiating block transfer ($totalBlocks blocks for ${imageBytes.size} bytes)...")
            val initTransferCmd = ByteArray(32).apply {
                this[0] = 0x00
                this[1] = 0x4A
                this[2] = 0x09
                this[3] = 0x01
                this[4] = 0x02
                this[5] = ((totalBlocks shr 8) and 0xFF).toByte()
                this[6] = (totalBlocks and 0xFF).toByte()
                System.arraycopy(sessionToken, 0, this, 28, 4)
            }
            val initResp = sendCustom(isoDep, initTransferCmd)
            log(false, "Block transfer initialized: ${bytesToHex(initResp)}")

            // 6. Send Blocks (1..totalBlocks) with Inter-Packet Pacing
            for (blockNum in 1..totalBlocks) {
                val offset = (blockNum - 1) * 180
                val remaining = imageBytes.size - offset
                val chunkRaw = ByteArray(180)

                if (remaining >= 180) {
                    System.arraycopy(imageBytes, offset, chunkRaw, 0, 180)
                } else {
                    // Final chunk: copy remaining bytes and pad rest with 0xFF
                    System.arraycopy(imageBytes, offset, chunkRaw, 0, remaining)
                    for (p in remaining until 180) {
                        chunkRaw[p] = 0xFF.toByte()
                    }
                }

                // 1-byte 8-bit additive checksum of the 180 bytes
                var sum: Byte = 0
                for (b in chunkRaw) {
                    sum = (sum + b).toByte()
                }

                // Assemble block packet: 00 4A 09 02 [blockNum BE 2 bytes] [180 bytes] [sum] [00] = 188 bytes
                val blockPacket = ByteArray(4 + 2 + 180 + 1 + 1)
                blockPacket[0] = 0x00
                blockPacket[1] = 0x4A
                blockPacket[2] = 0x09
                blockPacket[3] = 0x02
                blockPacket[4] = ((blockNum shr 8) and 0xFF).toByte()
                blockPacket[5] = (blockNum and 0xFF).toByte()
                System.arraycopy(chunkRaw, 0, blockPacket, 6, 180)
                blockPacket[186] = sum
                blockPacket[187] = 0x00

                val progress = blockNum.toFloat() / totalBlocks.toFloat()
                _transferState.value = NfcTransferState.Transferring(blockNum, totalBlocks, progress)

                sendCustom(isoDep, blockPacket)

                // Inter-packet pacing sleep
                if (interPacketDelayMs > 0) {
                    Thread.sleep(interPacketDelayMs)
                }
            }

            // For SN3H-G models (flagByte == 1 or 2), send trailing dummy block per VidaBay protocol
            if (flagByte == 1 || flagByte == 2) {
                val extraBlockNum = totalBlocks + 1
                log(false, "Sending extra protocol termination block ($extraBlockNum)...")
                val dummyBlock = ByteArray(4 + 2 + 180 + 1 + 1).apply {
                    this[0] = 0x00
                    this[1] = 0x4A
                    this[2] = 0x09
                    this[3] = 0x02
                    this[4] = ((extraBlockNum shr 8) and 0xFF).toByte()
                    this[5] = (extraBlockNum and 0xFF).toByte()
                    // 180 zeroes, sum = 0, trailer = 0
                }
                sendCustom(isoDep, dummyBlock)
            }

            log(false, "All $totalBlocks blocks written successfully!")

            // 7. Pre-Refresh Soak Delay (charge capacitor before high-draw screen refresh)
            _transferState.value = NfcTransferState.PreRefreshSoak
            log(false, "Pausing for ${preRefreshDelayMs}ms to charge capacitor before high-draw screen refresh...")
            Thread.sleep(preRefreshDelayMs)

            // 8. Screen Refresh & Polling
            _transferState.value = NfcTransferState.RefreshingScreen
            log(false, "Triggering E-Ink waveform screen refresh...")

            val refreshCmd = ByteArray(32).apply {
                this[0] = 0x00
                this[1] = 0x4A
                this[2] = 0x05
                this[3] = 0x00
                System.arraycopy(sessionToken, 0, this, 28, 4)
            }

            // Official SDK uses 40000ms timeout for the refresh phase
            isoDep.timeout = 40000

            _transferState.value = NfcTransferState.PollingStatus
            log(false, "Polling display refresh completion (supplying RF energy to capacitor)...")

            var refreshDone = false
            for (poll in 1..40) {
                if (!isoDep.isConnected) {
                    log(false, "Tag disconnected during screen refresh. Particle movement will complete on capacitor power.")
                    break
                }
                try {
                    val pollResp = sendCustom(isoDep, refreshCmd)
                    if (pollResp.size > 5 &&
                        pollResp[2] == 0x09.toByte() &&
                        pollResp[3] == 0x03.toByte() &&
                        pollResp[5] == 0x00.toByte()
                    ) {
                        refreshDone = true
                        log(false, "Refresh confirmed ready by tag (poll attempt $poll)!")
                        break
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Refresh poll $poll status: ${e.message}")
                    // If tag connection was permanently severed by OS, stop looping immediately
                    if (e is android.nfc.TagLostException || !isoDep.isConnected) {
                        log(false, "Tag connection ended during refresh (attempt $poll). Particles are flipping.")
                        break
                    }
                }
                Thread.sleep(150L)
            }

            if (refreshDone) {
                log(false, "Screen refresh confirmed successfully! Transfer complete.")
                _transferState.value = NfcTransferState.Success("Screen refresh complete!")
            } else {
                log(false, "Screen refresh cycle executed. Magnet may now be removed.")
                _transferState.value = NfcTransferState.Success("Image transferred and refresh finished.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Transfer failed", e)
            val errorMsg = e.message ?: "Unknown NFC transfer error"
            log(false, "Transfer failed: $errorMsg")
            _transferState.value = NfcTransferState.Error(errorMsg)
        } finally {
            isTransferring = false
            try {
                isoDep.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun sendCustom(isoDep: IsoDep, payload: ByteArray, maxRetries: Int = 3): ByteArray {
        val apdu = ByteArray(5 + payload.size).apply {
            this[0] = 0x00
            this[1] = 0x4A
            this[2] = 0x00
            this[3] = 0x00
            this[4] = payload.size.toByte()
            System.arraycopy(payload, 0, this, 5, payload.size)
        }

        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                if (attempt > 1) {
                    log(false, "Retrying APDU command (attempt $attempt/$maxRetries)...")
                    Thread.sleep(150L) // Allow capacitor to settle after a transient brownout
                }

                log(true, "TX: ${bytesToHex(apdu)}")
                val resp = isoDep.transceive(apdu)
                log(false, "RX: ${bytesToHex(resp)}")

                if (resp.size < 2) {
                    val errMsg = "APDU response too short (${resp.size} bytes: ${bytesToHex(resp)})"
                    log(false, "Warning: $errMsg (attempt $attempt/$maxRetries)")
                    lastException = IOException(errMsg)
                    continue
                }

                // Return payload without the 2-byte wrapper prefix
                return resp.copyOfRange(2, resp.size)
            } catch (e: Exception) {
                lastException = e
                log(false, "APDU transceive exception on attempt $attempt/$maxRetries: ${e.message}")
                if (e is android.nfc.TagLostException && !isoDep.isConnected) {
                    // Tag is completely out of RF field
                    break
                }
            }
        }

        throw lastException ?: IOException("APDU transceive failed after $maxRetries attempts")
    }

    private fun log(isTx: Boolean, message: String, hex: String? = null) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        _logFlow.tryEmit(LogEntry(time, isTx, message, hex))
        if (isTx) {
            Log.d(TAG, "[TX] $message")
        } else {
            Log.d(TAG, "[RX] $message")
        }
    }

    companion object {
        fun bytesToHex(bytes: ByteArray): String {
            val sb = java.lang.StringBuilder()
            for (b in bytes) {
                sb.append(String.format("%02X ", b))
            }
            return sb.toString().trim()
        }
    }
}
