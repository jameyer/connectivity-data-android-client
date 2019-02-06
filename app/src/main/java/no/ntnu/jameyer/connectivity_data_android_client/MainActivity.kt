package no.ntnu.jameyer.connectivity_data_android_client

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.support.design.widget.Snackbar
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v4.content.LocalBroadcastManager
import android.support.v7.app.AppCompatActivity
import android.view.View
import com.google.android.gms.common.api.ResolvableApiException
import kotlinx.android.synthetic.main.activity_main.*


class MainActivity : AppCompatActivity() {
    companion object {
        /**
         * Constant used for checking permissions.
         */
        private const val REQUEST_CHECK_PERMISSIONS = 0x1

        /**
         * Set by other classes followed by a [BROADCAST_RESOLVE].
         */
        var resolvableApiException: ResolvableApiException? = null

        /**
         * Used to broadcast text that should be displayed in the UI's TextView.
         */
        const val BROADCAST_TEXT = "BROADCAST_TEXT"

        /**
         * Used to broadcast text that should be displayed in the UI's Snackbar.
         */
        const val BROADCAST_SNACKBAR = "BROADCAST_SNACKBAR"

        /**
         * Used to tell this activity to startResolutionForResult(..) for the currently set
         * [resolvableApiException]
         */
        const val BROADCAST_RESOLVE = "BROADCAST_RESOLVE"

        /**
         * Used to tell the service to stop sending packets (so that it has some time to receive
         * replies for sent packets before it stops receiving packets).
         */
        const val BROADCAST_STOP_SENDING = "BROADCAST_STOP_SENDING"
    }

    private val TAG = MainActivity::class.java.simpleName

    private lateinit var broadcastReceiver: BroadcastReceiver

    private val broadcastIntentFilter = IntentFilter().apply {
        addAction(BROADCAST_TEXT)
        addAction(BROADCAST_SNACKBAR)
        addAction(BROADCAST_RESOLVE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        // Note that onReceive is always run on the UI thread
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                if (p1 == null) return

                val text = p1.getStringExtra("text")

                when (p1.action) {
                    BROADCAST_TEXT -> textView.text = text
                    BROADCAST_SNACKBAR -> {
                        val duration = p1.getIntExtra("duration", Snackbar.LENGTH_LONG)

                        showSnackbar(text, duration)
                    }
                    BROADCAST_RESOLVE -> resolvableApiException
                            ?.startResolutionForResult(this@MainActivity, REQUEST_CHECK_PERMISSIONS)
                }
            }
        }

        LocalBroadcastManager.getInstance(applicationContext)
                .registerReceiver(broadcastReceiver, broadcastIntentFilter)
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()

        startButton.isEnabled = !BackgroundService.isRunning
        stopButton.isEnabled = BackgroundService.isRunning
    }

    fun startUpdatesButtonHandler(view: View) {
        startService(Intent(this, BackgroundService::class.java))
        textView.text = "..."
        startButton.isEnabled = false
        stopButton.isEnabled = true
    }

    fun stopUpdatesButtonHandler(view: View) {
        LocalBroadcastManager.getInstance(applicationContext)
                .sendBroadcast(Intent(MainActivity.BROADCAST_STOP_SENDING))
        stopButton.isEnabled = false

        // Need to use a countDownInterval < 1000, else onTick(..) is not called for
        // the last tick (seems to be a known issue with CountDownTimer).
        object : CountDownTimer(5000, 500) {
            override fun onTick(millisUntilFinished: Long) {
                stopButton.text = "${(millisUntilFinished / 1000) + 1}"
            }

            override fun onFinish() {
                stopButton.text = "STOP"
                stopService(Intent(this@MainActivity, BackgroundService::class.java))
                startButton.isEnabled = true
                textView.text = "..."
            }
        }.start()
    }

    private fun showSnackbar(text: String, duration: Int) {
        Snackbar.make(findViewById<View>(android.R.id.content), text, duration).show()
    }

    private fun checkPermissions() {
        val neededPermissions = mutableListOf<String>()

        arrayOf(Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_NETWORK_STATE).forEach {
            if (ContextCompat.checkSelfPermission(this, it)
                    != PackageManager.PERMISSION_GRANTED) {
                // Permission is not granted
                // Should we show an explanation?
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, it)) {
                    // Show an explanation to the user *asynchronously* -- don't block
                    // this thread waiting for the user's response! After the user
                    // sees the explanation, try again to request the permission.
                    showSnackbar("Permissions required for measurements.", Snackbar.LENGTH_LONG)
                } else {
                    // No explanation needed, we can request the permission.
                    // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                    // app-defined int constant. The callback method gets the
                    // result of the request.
                    neededPermissions.add(it)
                }
            }
        }

        if (neededPermissions.size > 0) {
            ActivityCompat.requestPermissions(this,
                    neededPermissions.toTypedArray(), REQUEST_CHECK_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_CHECK_PERMISSIONS -> {
                permissions.forEachIndexed { index, permission ->
                    if (grantResults[index] == PackageManager.PERMISSION_DENIED) {
                        if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                            // Show an explanation to the user *asynchronously* -- don't block
                            // this thread waiting for the user's response! After the user
                            // sees the explanation, try again to request the permission.
                            showSnackbar("Permissions required for measurements.", Snackbar.LENGTH_LONG)
                        } else {
                            // Never ask again selected, or device policy prohibits the app from having that permission.
                            // So, disable that feature, or fall back to another situation...
                        }
                    }
                }
            }
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
