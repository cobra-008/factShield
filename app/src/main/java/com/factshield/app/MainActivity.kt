package com.factshield.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val PREFS_NAME = "factshield_prefs"
private const val KEY_DEMO_MODE = "demo_everywhere"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FactShieldTheme {
                OnboardingScreen()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Minimal theme wrapper
// ─────────────────────────────────────────────────────────────

@Composable
fun FactShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF020617),
            surface = Color(0xFF0F172A),
            primary = Color(0xFF38BDF8),
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

// ─────────────────────────────────────────────────────────────
//  Onboarding Screen
// ─────────────────────────────────────────────────────────────

@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var demoMode by remember { mutableStateOf(prefs.getBoolean(KEY_DEMO_MODE, false)) }
    val isServiceEnabled = remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(500)
        isServiceEnabled.value = isAccessibilityServiceEnabled(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF020617), Color(0xFF0F172A), Color(0xFF020617))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF0EA5E9), Color(0xFF6366F1))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("🛡️", fontSize = 48.sp)
            }
            Spacer(Modifier.height(24.dp))
            Text("FactShield", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(
                "Real-time misinformation shield for X",
                fontSize = 14.sp, color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(36.dp))
            HowItWorksCard()
            Spacer(Modifier.height(24.dp))
            ServiceStatusCard(isEnabled = isServiceEnabled.value)
            Spacer(Modifier.height(16.dp))

            val btnColor by animateColorAsState(
                targetValue = if (isServiceEnabled.value) Color(0xFF10B981) else Color(0xFF0EA5E9),
                animationSpec = tween(400), label = "btnColor"
            )
            Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = btnColor),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
            ) {
                Text(
                    if (isServiceEnabled.value) "✅  Service Active — Re-open Settings"
                    else "⚡  Enable Accessibility Service",
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White
                )
            }
            Spacer(Modifier.height(16.dp))
            PhysicalPhoneHintCard()
            Spacer(Modifier.height(24.dp))
            DemoModeToggle(enabled = demoMode) {
                demoMode = it
                prefs.edit().putBoolean(KEY_DEMO_MODE, it).apply()
            }
            Spacer(Modifier.height(32.dp))
            Text(
                "FactShield · Hackathon Prototype\nGroq Cloud + Google News RSS",
                fontSize = 11.sp, color = Color(0xFF334155), textAlign = TextAlign.Center
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Sub-components
// ─────────────────────────────────────────────────────────────

@Composable
fun HowItWorksCard() {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color(0xFF0F172A), tonalElevation = 4.dp) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("How It Works", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            listOf(
                Triple("🔵", "IDLE", "Floating avatar sits quietly in the top-right corner."),
                Triple("🔄", "SCANNING", "Pausing scroll triggers AI analysis automatically."),
                Triple("🟢", "GREEN", "Content aligns with credible live news sources."),
                Triple("🟡", "YELLOW", "Claims are unverified or use sensational framing."),
                Triple("🔴", "RED", "Contradicted by live news. Potential misinformation.")
            ).forEach { (icon, label, desc) ->
                Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Text(icon, fontSize = 16.sp, modifier = Modifier.width(28.dp))
                    Column {
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                        Text(desc, fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                }
            }
        }
    }
}

@Composable
fun ServiceStatusCard(isEnabled: Boolean) {
    val bgColor = if (isEnabled) Color(0xFF064E3B) else Color(0xFF1E1B4B)
    val dotColor = if (isEnabled) Color(0xFF10B981) else Color(0xFFF59E0B)
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = bgColor) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(dotColor))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (isEnabled) "Service is ACTIVE" else "Service is DISABLED", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                Text(if (isEnabled) "FactShield is monitoring X in real time." else "Enable the accessibility service below to start.", color = Color(0xFF94A3B8), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun PhysicalPhoneHintCard() {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color(0xFF1C1917)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("⚠️  Unlocking Restricted Settings (Android 13+)", fontWeight = FontWeight.Bold, color = Color(0xFFFBBF24), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            listOf(
                "1. Go to Android Settings → Apps → FactShield.",
                "2. Tap the ⋮ menu (top-right) → Allow Restricted Settings.",
                "3. Go to Settings → Accessibility → Installed Apps.",
                "4. Select FactShield and toggle it ON.",
                "5. Come back here — the status card above will turn green."
            ).forEach { step ->
                Text(step, fontSize = 12.sp, color = Color(0xFF94A3B8), modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
fun DemoModeToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color(0xFF0F172A)) {
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Demo Everywhere Mode", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                Text("Show the overlay in ALL apps (not just X). Useful for hackathon demos.", fontSize = 11.sp, color = Color(0xFF64748B))
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF0EA5E9),
                    uncheckedTrackColor = Color(0xFF334155)
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Utility
// ─────────────────────────────────────────────────────────────

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val serviceName = "${context.packageName}/${XFactAccessibilityService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(":").any { it.equals(serviceName, ignoreCase = true) }
}