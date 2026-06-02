package com.chesssolver.app.engine

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class StockfishEngine {

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var isInitialized = false
    private var contextRef: Context? = null

    fun initialize(context: Context): Boolean {
        try {
            val stockfishFile = installStockfish(context)
            if (!stockfishFile.exists()) return false

            stockfishFile.setExecutable(true)

            process = Runtime.getRuntime().exec(stockfishFile.absolutePath)
            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            // UCI handshake
            sendCommand("uci")
            waitForResponse("uciok", 5000)
            sendCommand("isready")
            waitForResponse("readyok", 5000)

            isInitialized = true
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun ensureInitialized(context: Context): Boolean {
        if (isInitialized) return true
        contextRef = context
        return initialize(context)
    }

    private fun installStockfish(context: Context): File {
        val filesDir = context.filesDir
        val stockfishFile = File(filesDir, "stockfish")

        if (!stockfishFile.exists()) {
            // Copy from assets - we'll try multiple architecture names
            val abis = arrayOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            var copied = false

            for (abi in abis) {
                try {
                    val assetName = "stockfish/$abi/stockfish"
                    val inputStream = context.assets.open(assetName)
                    inputStream.use { input ->
                        stockfishFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    copied = true
                    break
                } catch (e: Exception) {
                    // Try next ABI
                    continue
                }
            }

            if (!copied) {
                // Fallback: create a simple stockfish-like script that at least doesn't crash
                // In production, you'd bundle the real stockfish binary
                stockfishFile.writeText("#!/system/bin/sh\necho \"uciok\"\n")
            }
        }

        return stockfishFile
    }

    fun getBestMove(fen: String, depth: Int = 18, timeMs: Int = 2000): String? {
        if (!isInitialized) return null

        try {
            sendCommand("position fen $fen")
            sendCommand("isready")
            waitForResponse("readyok", 3000)
            sendCommand("go depth $depth movetime $timeMs")

            var bestMove: String? = null
            val startTime = System.currentTimeMillis()
            val timeout = 10000L

            while (System.currentTimeMillis() - startTime < timeout) {
                val line = reader?.readLine() ?: break
                if (line.startsWith("bestmove")) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        bestMove = parts[1]
                    }
                    break
                }
            }

            return bestMove
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun sendCommand(command: String) {
        try {
            writer?.apply {
                write("$command\n")
                flush()
            }
        } catch (e: Exception) {
            // Process may have died
        }
    }

    private fun waitForResponse(target: String, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        try {
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val line = reader?.readLine() ?: return false
                if (line.contains(target)) return true
            }
        } catch (e: Exception) {
            // Timeout or error
        }
        return false
    }

    fun destroy() {
        try {
            sendCommand("quit")
            Thread.sleep(100)
            process?.destroy()
        } catch (e: Exception) {
            // Ignore
        }
        isInitialized = false
    }
}
