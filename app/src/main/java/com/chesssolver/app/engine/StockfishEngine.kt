package com.chesssolver.app.engine

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Stockfish engine running as a native process.
 * Extracts the binary from assets on first run.
 * Falls back to pure Kotlin engine if native binary isn't available.
 */
class StockfishEngine {

    companion object {
        private const val TAG = "StockfishEngine"
        private const val ENGINE_FILENAME = "stockfish"
    }

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    var isInitialized = false
        private set

    private val fallbackEngine = ChessEngine()
    var useFallback = false
        private set

    private var engineThread = Executors.newSingleThreadExecutor()

    fun initialize(context: Context): Boolean {
        // Try native Stockfish first
        try {
            val binaryFile = installBinary(context)
            if (binaryFile != null && binaryFile.exists()) {
                binaryFile.setExecutable(true, false)
                binaryFile.setReadable(true, false)

                Log.d(TAG, "Starting Stockfish process: ${binaryFile.absolutePath}")
                
                process = ProcessBuilder(binaryFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                
                writer = OutputStreamWriter(process!!.outputStream)
                reader = BufferedReader(InputStreamReader(process!!.inputStream), 8192)

                // UCI handshake
                sendCommand("uci")
                if (waitForResponse("uciok", 5000)) {
                    sendCommand("setoption name Threads value 2")
                    sendCommand("setoption name Hash value 64")
                    sendCommand("isready")
                    if (waitForResponse("readyok", 5000)) {
                        isInitialized = true
                        useFallback = false
                        Log.d(TAG, "Stockfish engine initialized successfully!")
                        return true
                    }
                }
                
                // If we got here, UCI handshake failed
                Log.w(TAG, "UCI handshake failed, killing process")
                process?.destroy()
                process = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start native Stockfish: ${e.message}")
            process?.destroy()
            process = null
        }

        // Fallback to pure Kotlin engine
        Log.i(TAG, "Using pure Kotlin chess engine as fallback")
        useFallback = true
        isInitialized = true
        return true
    }

    fun getBestMove(fen: String, depth: Int = 16, timeMs: Int = 3000): String? {
        if (!isInitialized) return null

        if (useFallback) {
            return try {
                fallbackEngine.setPosition(fen)
                fallbackEngine.getBestMove(depth = 4, timeMs = timeMs)
            } catch (e: Exception) {
                Log.e(TAG, "Fallback engine error", e)
                null
            }
        }

        // Use native Stockfish
        try {
            sendCommand("ucinewgame")
            sendCommand("position fen $fen")
            sendCommand("isready")
            waitForResponse("readyok", 3000)
            sendCommand("go depth $depth movetime $timeMs")

            val startTime = System.currentTimeMillis()
            val timeout = (timeMs + 5000).toLong()

            while (System.currentTimeMillis() - startTime < timeout) {
                try {
                    val future = engineThread.submit<String?> { reader?.readLine() }
                    val line = future.get(2, TimeUnit.SECONDS)
                    
                    if (line == null) break
                    
                    if (line.startsWith("bestmove")) {
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 2) {
                            val move = parts[1]
                            Log.d(TAG, "Stockfish best move: $move")
                            return move
                        }
                        break
                    }
                } catch (e: TimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
            }

            Log.w(TAG, "Stockfish timed out, trying fallback engine")
            return try {
                fallbackEngine.setPosition(fen)
                fallbackEngine.getBestMove(depth = 4, timeMs = 2000)
            } catch (e: Exception) {
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Stockfish error, falling back", e)
            return try {
                fallbackEngine.setPosition(fen)
                fallbackEngine.getBestMove(depth = 4, timeMs = 2000)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun installBinary(context: Context): File? {
        val filesDir = context.filesDir
        val binaryFile = File(filesDir, ENGINE_FILENAME)

        // Check if we already have the correct binary
        if (binaryFile.exists() && binaryFile.canExecute()) {
            // Verify it's a real binary (not a placeholder script)
            val firstBytes = ByteArray(4)
            try {
                binaryFile.inputStream().use { it.read(firstBytes) }
                // ELF binaries start with 0x7F 'E' 'L' 'F'
                if (firstBytes[0] == 0x7F.toByte() && firstBytes[1] == 'E'.code.toByte()) {
                    Log.d(TAG, "Existing Stockfish binary looks valid")
                    return binaryFile
                }
                // It's a placeholder or corrupt, delete it
                Log.w(TAG, "Existing binary is not a valid ELF, replacing")
                binaryFile.delete()
            } catch (e: Exception) {
                binaryFile.delete()
            }
        }

        // Try to copy from assets
        val abis = if (android.os.Build.SUPPORTED_ABIS != null) {
            android.os.Build.SUPPORTED_ABIS.toList()
        } else {
            listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        for (abi in abis) {
            try {
                val assetName = "stockfish/$abi/stockfish"
                val inputStream = context.assets.open(assetName)
                inputStream.use { input ->
                    binaryFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Copied Stockfish binary for ABI: $abi")
                return binaryFile
            } catch (e: Exception) {
                Log.d(TAG, "No binary for ABI $abi: ${e.message}")
                continue
            }
        }

        Log.w(TAG, "No Stockfish binary found in assets for any ABI")
        return null
    }

    private fun sendCommand(command: String) {
        try {
            writer?.apply {
                write("$command\n")
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command: $command", e)
        }
    }

    private fun waitForResponse(target: String, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        try {
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val future = engineThread.submit<String?> { reader?.readLine() }
                try {
                    val line = future.get(1, TimeUnit.SECONDS)
                    if (line == null) return false
                    if (line.contains(target)) return true
                } catch (e: TimeoutException) {
                    continue
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for response", e)
        }
        return false
    }

    fun destroy() {
        try {
            sendCommand("quit")
            Thread.sleep(200)
            process?.destroyForcibly()
        } catch (e: Exception) {
            // Ignore
        }
        try {
            engineThread.shutdownNow()
        } catch (e: Exception) {
            // Ignore
        }
        engineThread = Executors.newSingleThreadExecutor()
        isInitialized = false
    }
}
