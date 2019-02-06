package no.ntnu.jameyer.connectivity_data_android_client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.Service
import android.content.*
import android.graphics.BitmapFactory
import android.location.Location
import android.net.*
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.app.NotificationCompat
import android.support.v4.content.LocalBroadcastManager
import android.telephony.CellInfoLte
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import no.ntnu.jameyer.connectivitydatashared.MeasurementData
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit


class BackgroundService: Service(), UDPClient.UDPClientListener {
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "my_channel_id"

        var isRunning = false
    }

    private val TAG = BackgroundService::class.java.simpleName

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private lateinit var settingsClient: SettingsClient
    private lateinit var locationSettingsRequest: LocationSettingsRequest
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var tcpClient: TCPClient
    private lateinit var uiTextExecutorService: ScheduledExecutorService

    /**
     * When we want to stop measuring we should stop sending a couple of seconds before we
     * also stop receiving and finally upload the measurements. This is because if we
     * stopped sending/receiving immediately the last packets sent might not have time to get
     * a reply and this will wrongly be seen as packet loss.
     */
    private var stopSending = false
    private var lastLocation: Location? = null
    private var lastDownstreamKbps: Int? = null
    private var lastUpstreamKbps: Int? = null
    private var lastNetworkType: String? = null

    private val udpClient = UDPClient(this)
    private val measurements = ConcurrentHashMap<Int, MeasurementData>()
    private val packetSendTimes = ConcurrentHashMap<Int, Long>()

    private val phoneStateListener = object : PhoneStateListener()  {
        var gsmAsuLevel: Int? = null
        var lteAsuLevel: Int? = null

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
            super.onSignalStrengthsChanged(signalStrength)

            if (signalStrength == null) return

            // Check for LTE specific ASU.
            if (telephonyManager.networkType == TelephonyManager.NETWORK_TYPE_LTE) {
                try {
                    val lteCellInfo = telephonyManager
                            .allCellInfo
                            .first { it is CellInfoLte } as CellInfoLte

                    if (lteCellInfo.cellSignalStrength.asuLevel != 99) { // 0 - 97, 99 = unavailable
                        lteAsuLevel = lteCellInfo.cellSignalStrength.asuLevel
                    }
                } catch (ex: SecurityException) {
                    Log.i("PhoneStateListener", "SecurityException: ${ex.message}")
                }
            }

            // TODO: Add UMTS in same manner as LTE?

            // Check for GSM specific ASU.
            if (signalStrength.gsmSignalStrength != 99) { // 0 - 31, 99 = unavailable
                gsmAsuLevel = signalStrength.gsmSignalStrength
            }

            Log.i("PhoneStateListener", "GSM asu: $gsmAsuLevel, LTE asu: $lteAsuLevel")
        }
    }

    private val networkCallback: ConnectivityManager.NetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val networkCapabilities = connectivityManager
                    .getNetworkCapabilities(network)
            updateNetworkData(networkCapabilities)

            super.onAvailable(network)
        }

        override fun onLinkPropertiesChanged(network: Network?, linkProperties: LinkProperties?) {
            val networkCapabilities = connectivityManager
                    .getNetworkCapabilities(network)
            updateNetworkData(networkCapabilities)

            super.onLinkPropertiesChanged(network, linkProperties)
        }

        override fun onCapabilitiesChanged(network: Network?, networkCapabilities: NetworkCapabilities?) {
            updateNetworkData(networkCapabilities)

            super.onCapabilitiesChanged(network, networkCapabilities)
        }

        private fun updateNetworkData(networkCapabilities: NetworkCapabilities?) {
            val networkInfo = connectivityManager.activeNetworkInfo
            lastNetworkType = if (networkInfo?.type == ConnectivityManager.TYPE_WIFI) {
                "WiFi"
            } else {
                networkInfo.subtypeName
            }

            lastDownstreamKbps = networkCapabilities?.linkDownstreamBandwidthKbps
            lastUpstreamKbps = networkCapabilities?.linkUpstreamBandwidthKbps

            Log.i(TAG, "NetworkType: $lastNetworkType")
        }
    }

    override fun onBind(p0: Intent?): IBinder {
        return Binder()
    }

    override fun onCreate() {
        super.onCreate()

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        settingsClient = LocationServices.getSettingsClient(this)
        tcpClient = TCPClient(applicationContext)

        locationRequest = LocationRequest().apply {
            interval = 1000
            fastestInterval = 1000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult?) {
                lastLocation = locationResult?.lastLocation

                // Log.i(TAG, "Location: (${lastLocation?.latitude}, ${lastLocation?.longitude})")
            }
        }

        locationSettingsRequest = LocationSettingsRequest.Builder()
                .apply { addLocationRequest(locationRequest) }.build()

        if (Build.VERSION.SDK_INT >= 26) {
            // Create notification channel.
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "channel", IMPORTANCE_DEFAULT)
            val notificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSending = false
        measurements.clear()
        packetSendTimes.clear()
        startLocationUpdates()
        udpClient.start()
        connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().build(), networkCallback)
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)

        LocalBroadcastManager.getInstance(applicationContext)
                .registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(p0: Context?, p1: Intent?) {
                        stopSending = true
                        udpClient.stopSend()
                    }

                }, IntentFilter(MainActivity.BROADCAST_STOP_SENDING))

        // Broadcast info that can be displayed in the UI at a fixed interval.
        uiTextExecutorService = Executors.newScheduledThreadPool(1).apply {
            scheduleWithFixedDelay({
                try {
                    LocalBroadcastManager.getInstance(applicationContext)
                            .sendBroadcast(Intent(MainActivity.BROADCAST_TEXT).apply {
                                val locationText = if (lastLocation == null) {
                                    "Unknown"
                                } else {
                                    "${"%.4f".format(Locale.ENGLISH, lastLocation?.latitude)}, ${"%.4f".format(Locale.ENGLISH, lastLocation?.longitude)}"
                                }

                                this.putExtra("text", "Packets: ${measurements.size}\n" +
                                        "Location: $locationText\n" +
                                        "Network Type: $lastNetworkType\n" +
                                        "Signal: ${phoneStateListener.gsmAsuLevel}, ${phoneStateListener.lteAsuLevel}")
                            })
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }, 0, 1000, TimeUnit.MILLISECONDS)
        }

        isRunning = true
        startForeground(1, createNotification())

        return START_STICKY
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("Running..")
                    .setAutoCancel(true)
                    .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                    .setSmallIcon(R.drawable.ic_notification)
                    .build()
        } else {
            NotificationCompat.Builder(this)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("Running..")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                    .setSmallIcon(R.drawable.ic_notification)
                    .build()
        }
    }

    // From UDPClientListener interface.
    override fun packetSent(packetId: Int, sendTime: Long) {
        if (stopSending) return

        if (lastLocation == null) {
            Log.i(TAG, "Location not known.")
            return
        }

        val data = MeasurementData(
                packetId,
                lastLocation!!.latitude,
                lastLocation!!.longitude,
                lastLocation!!.accuracy,
                lastLocation!!.speed,
                lastLocation!!.bearing,
                null,
                null,
                null,
                lastNetworkType,
                lastDownstreamKbps,
                lastUpstreamKbps,
                null,
                phoneStateListener.gsmAsuLevel,
                phoneStateListener.lteAsuLevel)

        packetSendTimes[packetId] = sendTime
        measurements[packetId] = data
    }

    // From UDPClientListener interface.
    override fun packetReceived(packetId: Int, replyTime: Long, serverReplyTime: Long) {
        val data = measurements[packetId]
        if (data == null) {
            Log.w(TAG, "Could not find packet with id $packetId")
            return
        }

        val sendTime = packetSendTimes[packetId]
        if (sendTime != null) {
            data.roundTripTime = (replyTime - sendTime).toInt()
        }

        data.serverReplyTime = serverReplyTime
    }

    override fun onDestroy() {
        // Stop everything and upload current data to server.
        udpClient.stop()
        stopLocationUpdates()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        uiTextExecutorService.shutdown()

        LocalBroadcastManager.getInstance(applicationContext)
                .sendBroadcast(Intent(MainActivity.BROADCAST_SNACKBAR).apply {
                    this.putExtra("text", "Uploading data..")
                })

        val data = measurements.values.map { it.toString() }.toTypedArray()
        tcpClient.uploadToServer(data)
        isRunning = false

        super.onDestroy()
    }

    private fun startLocationUpdates() {
        val task: Task<LocationSettingsResponse> = settingsClient
                .checkLocationSettings(locationSettingsRequest)

        task.addOnSuccessListener {
            try {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
            } catch (ex: SecurityException) {
                Log.i(TAG, "SecurityException: ${ex.message}")
            }
        }

        task.addOnFailureListener {
            try {
                val apiException = it as ApiException
                val statusCode = apiException.statusCode

                when (statusCode) {
                    LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> {
                        try {
                            MainActivity.resolvableApiException = apiException as ResolvableApiException
                            LocalBroadcastManager.getInstance(applicationContext)
                                    .sendBroadcast(Intent(MainActivity.BROADCAST_RESOLVE))
                        } catch (sie: IntentSender.SendIntentException) {
                            LocalBroadcastManager.getInstance(applicationContext)
                                    .sendBroadcast(Intent(MainActivity.BROADCAST_TEXT).apply {
                                        this.putExtra("text", "PendingIntent unable to execute request.")
                                    })
                        }
                    }

                    LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE -> {
                        LocalBroadcastManager.getInstance(applicationContext)
                                .sendBroadcast(Intent(MainActivity.BROADCAST_TEXT).apply {
                                    this.putExtra("text", "Location settings are inadequate, and cannot be fixed here. Fix in Settings.")
                                })
                    }
                }
            } catch (ex: Exception) {
                Log.i(TAG, "Exception: ${ex.message}")
            }
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}