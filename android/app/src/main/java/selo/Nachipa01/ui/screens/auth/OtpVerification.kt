package selo.Nachipa01.ui.screens.auth

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.OtpChallenge
import selo.Nachipa01.data.repo.DemoBackend
import selo.Nachipa01.domain.Validation
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo
import java.security.MessageDigest

/** Pulls the 6-digit PIN out of an SMS body (pure; unit-tested). */
fun extractOtp(message: String?): String? = message?.let { Regex("(?<!\\d)(\\d{6})(?!\\d)").find(it)?.groupValues?.get(1) }

/**
 * Listens for the Beem OTP SMS without the SMS permission:
 *  - SMS Retriever (zero-tap) when the SMS ends with this app's 11-character hash (configured in the Beem template);
 *  - SMS User Consent (one tap on "Allow") for any SMS containing a code — works even without the hash.
 * Both stop after one message or five minutes (Play services limit); the UI shows the spinner for 30 s.
 */
@Composable
private fun rememberSmsCodeListener(active: Boolean, onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val current by rememberUpdatedState(onCode)
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) extractOtp(r.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE))?.let(current)
    }
    DisposableEffect(active) {
        if (!active || Build.FINGERPRINT == "robolectric") return@DisposableEffect onDispose { }
        val client = SmsRetriever.getClient(ctx)
        runCatching { client.startSmsRetriever(); client.startSmsUserConsent(null) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras = intent.extras ?: return
                @Suppress("DEPRECATION") val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode != CommonStatusCodes.SUCCESS) return
                extractOtp(extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE))?.let { current(it); return }
                @Suppress("DEPRECATION") val consentIntent = extras.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)
                if (consentIntent != null) runCatching { consent.launch(consentIntent) }
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION), SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
}

/**
 * BEEM-OTP-001 OTP screen: sends the PIN, auto-detects it from the SMS (WhatsApp-style) and verifies immediately,
 * or lets the user type it into six boxes. 60-second countdown; "Resend code" unlocks when it reaches zero.
 */
@Composable
fun OtpVerification(msisdn: String, verified: Boolean, onVerified: (token: String) -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var challenge by remember(msisdn) { mutableStateOf<OtpChallenge?>(null) }
    var code by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var left by remember { mutableIntStateOf(0) }
    var listening by remember { mutableStateOf(false) }
    var listenLeft by remember { mutableIntStateOf(0) }

    fun verify(pin: String) {
        val c = challenge ?: return
        if (verifying) return
        verifying = true; err = null
        scope.launch {
            runCatching { app.backend.verifySignupOtp(msisdn, c.pinId, pin) }
                .onSuccess { listening = false; onVerified(it) }
                .onFailure { err = it.message ?: "That code is incorrect. Please try again."; code = "" }
            verifying = false
        }
    }
    fun send() = scope.launch {
        sending = true; err = null; code = ""
        runCatching { app.backend.sendSignupOtp(msisdn) }
            .onSuccess { challenge = it; left = it.expiresInSec; listening = true; listenLeft = 30 }
            .onFailure { err = it.message ?: "We couldn't send an SMS right now. Please try again." }
        sending = false
    }
    LaunchedEffect(msisdn) { if (!verified && challenge == null) send() }
    LaunchedEffect(challenge) { while (left > 0) { delay(1_000); left-- } }
    LaunchedEffect(listening, challenge) { while (listening && listenLeft > 0) { delay(1_000); listenLeft-- }; listening = false }
    rememberSmsCodeListener(active = listening && !verified) { pin -> code = pin; verify(pin) }

    H("Verify your number", 18)
    if (verified) { NoteBox("Number verified.", Tone.OK, Icons.Outlined.CheckCircle); return }
    Sub("We sent a 6-digit code to ${Validation.maskMsisdn(msisdn).let { formatIntl(msisdn) }}")
    PinInput(code, { v -> code = v; err = null; if (v.length == 6) verify(v) }, length = 6, reveal = true, error = err != null)
    if (listening && !verifying) Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Selo.Red)
        Text("  Verifying automatically…", color = Selo.Mute, fontSize = 13.sp)
    }
    if (verifying) Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Selo.Red); Text("  Checking the code…", color = Selo.Mute, fontSize = 13.sp)
    }
    ErrorText(err)
    if (challenge != null) Text(if (left > 0) "Code expires in %d:%02d".format(left / 60, left % 60) else "Code expired", color = if (left > 0) Selo.Mute else Selo.Red, fontSize = 13.sp)
    PrimaryButton("Verify →", loading = verifying, whyDisabled = if (challenge == null) "We're still sending your code…" else "Enter all 6 digits from the SMS.", enabled = code.length == 6 && challenge != null) { verify(code) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Didn't receive it?", color = Selo.Mute, fontSize = 13.sp)
        TextButton({ send() }, enabled = !sending && left == 0) { Text(if (sending) "Sending…" else "Resend code", color = if (left == 0) Selo.Red else Selo.Mute, fontWeight = FontWeight.Bold) }
    }
    (app.backend as? DemoBackend)?.v64?.lastOtp?.let { Text("DEMO ONLY · SMS code: $it", color = Selo.AmberDark, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

private fun formatIntl(e164: String): String {
    val d = e164.filter { it.isDigit() }
    return if (d.length == 12) "+${d.substring(0, 3)} ${d.substring(3, 6)} ${d.substring(6, 9)} ${d.substring(9)}" else e164
}

/**
 * The 11-character hash Beem's OTP SMS must end with for zero-tap SMS Retriever. Computed from this APK's signing
 * certificate; log it once from a release build and paste it into the Beem OTP template.
 */
object AppHash {
    fun compute(ctx: Context): String? = runCatching {
        val sigs = if (Build.VERSION.SDK_INT >= 28) ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners
            else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures!!
        val md = MessageDigest.getInstance("SHA-256")
        md.update("${ctx.packageName} ${sigs[0].toCharsString()}".toByteArray(Charsets.UTF_8))
        Base64.encodeToString(md.digest().copyOfRange(0, 9), Base64.NO_PADDING or Base64.NO_WRAP).substring(0, 11)
    }.getOrNull()
}
