package com.kaia.meshvpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            this.text = "Kaia Mesh VPN\n\nVPN peer-to-peer basé sur IPFS.\nAucun serveur central : le tunnel est chiffré (Noise) et les pairs se découvrent via le DHT IPFS."
            textSize = 16f
            setPadding(48, 96, 48, 32)
        }
        val button = Button(this).apply { this.text = "Activer le tunnel" }
        button.setOnClickListener { requestVpn() }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(text)
            addView(button)
        }
        setContentView(layout)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.FOREGROUND_SERVICE),
                42
            )
        }
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 7)
        } else {
            startService(Intent(this, MeshVpnService::class.java))
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) {
            startService(Intent(this, MeshVpnService::class.java))
        }
    }
}
