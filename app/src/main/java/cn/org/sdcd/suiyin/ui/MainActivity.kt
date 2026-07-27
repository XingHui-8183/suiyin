package cn.org.sdcd.suiyin.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cn.org.sdcd.suiyin.R
import cn.org.sdcd.suiyin.common.Prefs
import cn.org.sdcd.suiyin.databinding.ActivityMainBinding
import cn.org.sdcd.suiyin.service.CoreService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            proceedAfterPermission()
        } else {
            Toast.makeText(this, "需要授予所有权限才能正常使用", Toast.LENGTH_SHORT).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkAndSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRoleSelection()
        updateRoleButtonsEnabled(false)
        checkPermissions()
    }

    private fun updateRoleButtonsEnabled(enabled: Boolean) {
        binding.btnHost.isEnabled = enabled
        binding.btnSlave.isEnabled = enabled
        binding.tvPermissionHint.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun setupRoleSelection() {
        binding.btnHost.setOnClickListener {
            selectRole(Prefs.ROLE_HOST)
        }

        binding.btnSlave.setOnClickListener {
            selectRole(Prefs.ROLE_SLAVE)
        }
    }

    private fun selectRole(role: String) {
        Prefs.setRole(role)
        CoreService.start(this)
        navigateToRoleUI(role)
    }

    private fun navigateToRoleUI(role: String) {
        if (role == Prefs.ROLE_HOST) {
            startActivity(Intent(this, HostActivity::class.java))
        } else {
            startActivity(Intent(this, SlaveActivity::class.java))
        }
        finish()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.RECEIVE_SMS)
        permissions.add(Manifest.permission.READ_SMS)
        permissions.add(Manifest.permission.READ_PHONE_STATE)

        val needRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(needRequest.toTypedArray())
        } else {
            proceedAfterPermission()
        }
    }

    private fun proceedAfterPermission() {
        updateRoleButtonsEnabled(true)
        if (Prefs.hasRole()) {
            CoreService.start(this)
            navigateToRoleUI(Prefs.getRole())
        }
    }

    private fun checkAndSetup() {
        checkPermissions()
    }
}
