package no.ntnu.jameyer.connectivity_data_android_client

import android.content.Context
import android.content.Intent
import android.support.v4.content.LocalBroadcastManager
import java.io.ObjectOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class TCPClient(private val context: Context) {
    companion object {
        /**
         * Destination address for incoming/outgoing traffic.
         */
        private const val SERVER_ADDRESS = "100.100.100.100"

        private const val SERVER_PORT = 1234
    }

    fun uploadToServer(data: Array<String>) {
        thread {
            var socket: Socket? = null

            try {
                socket = Socket().apply {
                    connect(InetSocketAddress(SERVER_ADDRESS, SERVER_PORT), 10000)
                }

                val writer = ObjectOutputStream(socket.outputStream)
                writer.writeObject(data)
                writer.flush()

                LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(MainActivity.BROADCAST_SNACKBAR).apply {
                    this.putExtra("text", "Success. Data uploaded.")
                })
            } catch (ex: SocketTimeoutException) {
                LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(MainActivity.BROADCAST_SNACKBAR).apply {
                    this.putExtra("text", "TCPClient: Failed to connect.")
                })
            } catch (ex: Exception) {
                println("Exception: $ex")
            } finally {
                socket?.close()
            }
        }
    }
}