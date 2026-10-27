package com.jarvis.agent.safety

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.agent.tools.RiskLevel
import kotlinx.coroutines.launch

/**
 * Full-screen confirmation that shows exactly what Jarvis is about to do. Shown over the lock screen.
 * HIGH-risk actions can only be approved after biometric / device-credential authentication.
 * Built with plain views so it stays small and starts fast from the background.
 */
class ConfirmActivity : FragmentActivity() {

    private var requestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        requestId = intent.getStringExtra(EXTRA_ID)
        val req = ConfirmationBroker.current.value
        if (req == null || req.id != requestId) {
            finish()
            return
        }
        setContentView(buildUi(req))
        // Close automatically if the request is decided elsewhere (voice, timeout, kill switch).
        lifecycleScope.launch {
            ConfirmationBroker.current.collect { if (it?.id != requestId) finish() }
        }
    }

    private fun buildUi(req: ConfirmationRequest): ScrollView {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val high = req.level == RiskLevel.HIGH
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.parseColor("#0B1320"))
        }
        root.addView(TextView(this).apply {
            text = if (high) "⚠ HIGH RISK — fingerprint/PIN zaroori" else "Tasdeeq karein (confirm)"
            setTextColor(Color.parseColor(if (high) "#EF4444" else "#F59E0B"))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = req.summary
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(0, pad, 0, pad / 2)
        })
        if (req.details.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = req.details
                setTextColor(Color.parseColor("#E6EDF7"))
                setBackgroundColor(Color.parseColor("#14213A"))
                textSize = 16f
                setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
                setTextIsSelectable(true)
            })
        }
        root.addView(TextView(this).apply {
            text = "Tool: ${req.toolName}" + if (req.level == RiskLevel.MEDIUM) "\nAap \"haan\" ya \"nahi\" bol bhi sakte hain." else ""
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, pad / 2, 0, pad)
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(Button(this).apply {
            text = "Nahi / Cancel"
            setOnClickListener { decide(false) }
        })
        buttons.addView(Button(this).apply {
            text = if (high) "Fingerprint se manzoor" else "Haan / Approve"
            setOnClickListener { if (high) authenticateThenApprove() else decide(true) }
        })
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1320"))
            addView(root)
        }
    }

    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private fun authenticateThenApprove() {
        val can = BiometricManager.from(this).canAuthenticate(authenticators())
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            // No secure lock screen: HIGH-risk actions are never allowed without one.
            android.widget.Toast.makeText(
                this, "Pehle phone par fingerprint ya screen lock lagayein. Yeh kaam ruk gaya.", android.widget.Toast.LENGTH_LONG,
            ).show()
            decide(false)
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = decide(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancelled or locked out: stay on screen so they can retry or press Cancel.
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Jarvis: high-risk action")
                .setSubtitle(ConfirmationBroker.current.value?.summary ?: "")
                .setAllowedAuthenticators(authenticators())
                .build(),
        )
    }

    private fun decide(approved: Boolean) {
        requestId?.let { ConfirmationBroker.resolve(it, approved) }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        decide(false)
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    companion object {
        private const val EXTRA_ID = "request_id"

        fun intent(context: Context, id: String): Intent =
            Intent(context, ConfirmActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
