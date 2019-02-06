package no.ntnu.jameyer.connectivity_data_android_client

import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class UDPClient(private val listener: UDPClientListener) {
    companion object {
        /**
         * Destination address for outgoing traffic.
         */
        private const val SERVER_ADDRESS = "100.100.100.100"

        /**
         * Port to send to and listen port for incoming traffic.
         */
        private const val PORT = 1235

        /**
         * How long to wait before starting to send after [start] is called. This delay is
         * introduced so that the socket is initialized and ready to receive data before
         * we start sending.
         */
        const val INITIAL_SEND_DELAY = 100L

        /**
         * How often we send to the UDP Server to test round trip sendTime and packet loss.
         * */
        private const val SEND_INTERVAL_MS = 250L

        /**
         * UDP data length. Important that sent and received packets have the same length.
         * This should be set to the same value in the client.
         */
        private const val DATAGRAM_DATA_LENGTH = 32
    }

    private val TAG = UDPClient::class.java.simpleName

    private val address = InetAddress.getByName(SERVER_ADDRESS)
    private var receiving = false
    private var packetNumber = AtomicInteger()
    private var socket: DatagramSocket? = null
    private var sendExecutorService: ScheduledExecutorService? = null

    interface UDPClientListener {
        fun packetSent(packetId: Int, sendTime: Long)

        fun packetReceived(packetId: Int, replyTime: Long, serverReplyTime: Long)
    }

    fun start() {
        receiving = true

        thread { // Receiving thread.
            val buffer = ByteArray(DATAGRAM_DATA_LENGTH)

            try {
                socket = DatagramSocket(PORT)

                while (receiving) {
                    val packet = DatagramPacket(buffer, buffer.size)

                    socket?.receive(packet)

                    // Handle this on separate thread so we are immediately ready to receive more data.
                    thread { handleReceivedPacket(packet) }
                }
            } catch (ex: Exception) {
                // println("Exception: $ex")
            }
        }

        // Sending thread (repeated by use of an ExecutorService).
        sendExecutorService = Executors.newScheduledThreadPool(1).apply {
            scheduleWithFixedDelay({ send() }, INITIAL_SEND_DELAY, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }

        Log.i(TAG, "Started." )
    }

    fun stopSend() {
        sendExecutorService?.shutdown()
    }

    fun stop() {
        receiving = false
        socket?.close()
        sendExecutorService?.shutdown()

        Log.i(TAG, "Stopped." )
    }

    private fun handleReceivedPacket(packet: DatagramPacket) {
        // Take timestamp immediately to get time as accurately as possible. Padding, stripping
        // converting etc. below could take a few ms (although probably negligible).
        val time = SystemClock.elapsedRealtime()
        val idBytes = ByteArray(4)
        val timeBytes = ByteArray(8)
        // First 4 bytes are the packet id
        System.arraycopy(packet.data, 0, idBytes, 0, 4)
        // Next 8 bytes are the timestamp
        System.arraycopy(packet.data, 4, timeBytes, 0, 8)

        val receivedPacketId = ByteBuffer.wrap(idBytes).int
        val receivedReplyTime = ByteBuffer.wrap(timeBytes).long

        listener.packetReceived(receivedPacketId, time, receivedReplyTime)
    }

    private fun send() {
        val packetId = packetNumber.getAndIncrement()
        val buffer = ByteBuffer.allocate(DATAGRAM_DATA_LENGTH).putInt(packetId).array()
        val packet = DatagramPacket(buffer, buffer.size, address, PORT)

        // If sending fails because of no connection following is thrown:
        // Exception: java.net.SocketException: sendto failed: ENETUNREACH (Network is unreachable)
        try {
            socket?.send(packet)
        } catch (ex: Exception){
            println("Exception: $ex")
        }

        listener.packetSent(packetId, SystemClock.elapsedRealtime())
    }
}