package org.matrix.vector.daemon.env

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.IOException
import org.matrix.vector.daemon.*
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.ipc.CliHandler

private const val TAG = "VectorCliSocket"

object CliSocketServer {

  private var isRunning = false

  fun start() {
    if (isRunning) return
    isRunning = true

    // Use a dedicated Thread for the blocking accept() loop
    val serverThread = Thread {
      try {
        val cliSocket: String = FileSystem.setupCli()

        val vectorSocket = LocalSocket()
        val address = LocalSocketAddress(cliSocket, LocalSocketAddress.Namespace.FILESYSTEM)
        vectorSocket.bind(address)
        val server = LocalServerSocket(vectorSocket.fileDescriptor)

        Log.d(TAG, "Cli socket server created at ${cliSocket}")
        while (!Thread.currentThread().isInterrupted) {
          try {
            // This blocks until a command is run
            val clientSocket = server.accept()

            // We handle each client in a nested try-catch.
            // If a specific command crashes, we just close that socket and continue.
            handleClient(clientSocket)
          } catch (e: IOException) {
            Log.w(TAG, "Error accepting client connection", e)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Fatal CLI Server error. CLI commands will be unavailable.", e)
      } finally {
        isRunning = false
      }
    }

    serverThread.name = "VectorCliListener"
    serverThread.priority = Thread.MIN_PRIORITY // Run as background task
    serverThread.start()
  }

  private fun handleClient(socket: LocalSocket) {
    try {
      val input = DataInputStream(socket.inputStream)
      val output = DataOutputStream(socket.outputStream)

      // Read & Verify Security Token (UUID MSB/LSB)
      val msb = input.readLong()
      val lsb = input.readLong()
      if (msb != BuildConfig.CLI_TOKEN_MSB || lsb != BuildConfig.CLI_TOKEN_LSB) {
        socket.close()
        return
      }

      val requestJson = input.readUTF()
      val request = VectorIPC.gson.fromJson(requestJson, CliRequest::class.java)

      // Intercept Log Streaming specifically before CliHandler
      if (request.command == "log" && request.action == "stream") {
        val verbose = request.options["verbose"] as? Boolean ?: false
        val logFile = if (verbose) LogcatMonitor.getVerboseLog() else LogcatMonitor.getModulesLog()

        if (logFile != null && logFile.exists()) {
          val response = CliResponse(success = true, isFdAttached = true)
          output.writeUTF(VectorIPC.gson.toJson(response))

          // Open file and get raw FileDescriptor
          val fis = FileInputStream(logFile)
          val fd = fis.fd

          // Attach FD to the next write operation
          socket.setFileDescriptorsForSend(arrayOf(fd))
          output.write(1) // Trigger byte to "carry" the ancillary FD data

          // fis is closed when the socket/method finishes
          return
        } else {
          output.writeUTF(
              VectorIPC.gson.toJson(CliResponse(success = false, error = "Log file not found.")))
          return
        }
      }

      // Standard commands go to CliHandler as usual
      val response = CliHandler.execute(request)
      output.writeUTF(VectorIPC.gson.toJson(response))
    } finally {
      socket.close()
    }
  }
}
