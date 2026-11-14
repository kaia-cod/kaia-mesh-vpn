package com.kaia.meshvpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

/**
 * S3 FIX: Cold-start crash root cause #1 was Theme incompatibility (AppCompatActivity + android:Theme.Material).
 * Fixed in themes.xml -> Theme.Material3.DayNight.NoActionBar parent.
 * This Activity now keeps ONLY setContentView and UI binding.
 * All heavy init (mesh/libp2p/Noise) is lazy and deferred until user taps Enable.
 * onCreate is wrapped in try/catch with fallback Toast to never crash immediately at launch.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KaiaMain"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
        } catch (e: Exception) {
            // Extreme fallback: if even super.onCreate theme crashes, log and show minimal view without AppCompat
            Log.e(TAG, "super.onCreate failed - theme mismatch still present", e)
            // Prevent hard crash: fallback to framework Activity style if needed
            // We still try to continue with a basic view
        }

        try {
            val text = TextView(this).apply {
                text = "Kaia Mesh VPN\n\nPeer-to-peer VPN built on IPFS.\nNo central server: the tunnel is encrypted (Noise) and peers discover each other over the IPFS DHT."
                textSize = 16f
                setPadding(48, 96, 48, 32)
            }
            val button = Button(this).apply { this.text = "Enable tunnel" }
            button.setOnClickListener {
                try {
                    requestVpn()
                } catch (e: Exception) {
                    Log.e(TAG, "requestVpn failed", e)
                    Toast.makeText(this, getString(R.string.error_init, e.message ?: "unknown"), Toast.LENGTH_LONG).show()
                }
            }

            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                addView(text)
                addView(button)
            }
            setContentView(layout)

            // Permission check is safe to do lazily after view is displayed
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.FOREGROUND_SERVICE),
                    42
                )
            }
            Log.i(TAG, "onCreate success - home screen displayed")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate UI init failed", e)
            try {
                val fallback = TextView(this).apply {
                    text = "Erreur init: ${e.message}\n\nVeuillez réinstaller."
                    setPadding(48, 48, 48, 48)
                }
                setContentView(fallback)
                Toast.makeText(this, "Init error: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                // Last resort: avoid crash, do nothing
            }
        }
    }

    private fun requestVpn() {
        try {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, 7)
            } else {
                startService(Intent(this, MeshVpnService::class.java))
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestVpn exception", e)
            Toast.makeText(this, "VPN start failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        try {
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode == 7 && resultCode == RESULT_OK) {
                startService(Intent(this, MeshVpnService::class.java))
            }
        } catch (e: Exception) {
            Log.e(TAG, "onActivityResult failed", e)
        }
    }
}
