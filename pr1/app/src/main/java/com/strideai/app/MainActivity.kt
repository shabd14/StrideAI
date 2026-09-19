package com.strideai.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

private val Ink = Color(0xFF050712)
private val Panel = Color(0xFF11182A)
private val PanelEdge = Color(0xFF26344F)
private val Neon = Color(0xFF76F7E9)
private val Violet = Color(0xFF9A8CFF)
private val Orange = Color(0xFFFFB86B)
private val Muted = Color(0xFF9BA9C2)

class MainActivity : ComponentActivity() {
    private val stepViewModel: StepViewModel by viewModels {
        StepViewModel.Factory(application)
    }

    private val motionPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        stepViewModel.setPermission(granted)
        if (granted) stepViewModel.startTracking()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.navigationBarColor = AndroidColor.rgb(5, 7, 18)
        val granted = hasMotionPermission()
        stepViewModel.setPermission(granted)
        setContent {
            StrideAITheme {
                StrideApp(
                    state = stepViewModel.uiState,
                    onRequestPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            motionPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        } else {
                            stepViewModel.setPermission(true)
                            stepViewModel.startTracking()
                        }
                    },
                    onApplyAiGoal = stepViewModel::applyAiGoal
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasMotionPermission()) stepViewModel.startTracking()
    }

    override fun onPause() {
        stepViewModel.stopTracking()
        super.onPause()
    }

    private fun hasMotionPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
}

data class DayPoint(val label: String, val steps: Int)

data class StepUiState(
    val steps: Int = 0,
    val goal: Int = 8_000,
    val permissionGranted: Boolean = false,
    val hasStepSensor: Boolean = true,
    val usesStepCounter: Boolean = true,
    val week: List<DayPoint> = emptyList()
) {
    val progress: Float get() = (steps.toFloat() / goal).coerceIn(0f, 1f)
    val distanceKm: Float get() = steps * 0.00075f
    val calories: Int get() = (steps * 0.04f).roundToInt()
    val activeMinutes: Int get() = max(0, (steps / 105f).roundToInt())
}

class StepViewModel(application: Application) : ViewModel(), SensorEventListener {
    private val preferences = application.getSharedPreferences("stride_data", Context.MODE_PRIVATE)
    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val counterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val detectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private var isTracking = false
    private var activeSensor: Sensor? = null

    var uiState by mutableStateOf(loadState())
        private set

    fun setPermission(granted: Boolean) {
        uiState = uiState.copy(permissionGranted = granted)
    }

    fun startTracking() {
        if (isTracking || !uiState.permissionGranted) return
        activeSensor = counterSensor ?: detectorSensor
        if (activeSensor == null) {
            uiState = uiState.copy(hasStepSensor = false)
            return
        }
        isTracking = sensorManager.registerListener(this, activeSensor, SensorManager.SENSOR_DELAY_UI)
        uiState = uiState.copy(
            hasStepSensor = isTracking,
            usesStepCounter = activeSensor?.type == Sensor.TYPE_STEP_COUNTER
        )
    }

    fun stopTracking() {
        if (!isTracking) return
        sensorManager.unregisterListener(this)
        isTracking = false
    }

    fun applyAiGoal() {
        val average = uiState.week.map { it.steps }.filter { it > 0 }.average()
        val baseline = if (average.isNaN()) 6_000 else average
        val suggested = (baseline * 1.12).roundToInt().coerceIn(4_000, 12_000)
        val rounded = (suggested / 500) * 500
        preferences.edit().putInt(KEY_GOAL, rounded).apply()
        uiState = uiState.copy(goal = rounded)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.isEmpty()) return
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> updateFromCounter(event.values[0].toLong())
            Sensor.TYPE_STEP_DETECTOR -> addDetectorStep()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateFromCounter(rawCount: Long) {
        rolloverIfNeeded(rawCount)
        val savedSteps = preferences.getInt(KEY_STEPS, 0)
        var baseline = preferences.getLong(KEY_BASELINE, BASELINE_UNSET)
        // The hardware counter resets after a reboot. Preserve today's saved total if that happens.
        if (baseline == BASELINE_UNSET || rawCount < baseline) {
            baseline = rawCount - savedSteps
        }
        val measured = max(savedSteps, (rawCount - baseline).coerceAtLeast(0).toInt())
        persist(steps = measured, baseline = baseline)
    }

    private fun addDetectorStep() {
        rolloverIfNeeded(null)
        persist(steps = preferences.getInt(KEY_STEPS, 0) + 1)
    }

    private fun rolloverIfNeeded(rawCount: Long?) {
        val today = todayKey()
        val storedDay = preferences.getString(KEY_DAY, null)
        if (storedDay != null && storedDay != today) {
            preferences.edit()
                .putInt("history_$storedDay", preferences.getInt(KEY_STEPS, 0))
                .putInt(KEY_STEPS, 0)
                .putLong(KEY_BASELINE, rawCount ?: BASELINE_UNSET)
                .putString(KEY_DAY, today)
                .apply()
        } else if (storedDay == null) {
            preferences.edit().putString(KEY_DAY, today).apply()
        }
    }

    private fun persist(steps: Int, baseline: Long = preferences.getLong(KEY_BASELINE, BASELINE_UNSET)) {
        preferences.edit()
            .putInt(KEY_STEPS, steps)
            .putLong(KEY_BASELINE, baseline)
            .putString(KEY_DAY, todayKey())
            .apply()
        uiState = uiState.copy(steps = steps, week = loadWeek())
    }

    private fun loadState(): StepUiState {
        rolloverIfNeeded(null)
        return StepUiState(
            steps = preferences.getInt(KEY_STEPS, 0),
            goal = preferences.getInt(KEY_GOAL, 8_000),
            hasStepSensor = counterSensor != null || detectorSensor != null,
            usesStepCounter = counterSensor != null,
            week = loadWeek()
        )
    }

    private fun loadWeek(): List<DayPoint> {
        val formatter = DateTimeFormatter.ofPattern("EE")
        val current = LocalDate.now()
        return (6 downTo 0).map { offset ->
            val day = current.minusDays(offset.toLong())
            val steps = if (day == current) preferences.getInt(KEY_STEPS, 0)
            else preferences.getInt("history_${day}", 0)
            DayPoint(day.format(formatter).take(1).uppercase(), steps)
        }
    }

    companion object {
        private const val KEY_DAY = "current_day"
        private const val KEY_STEPS = "today_steps"
        private const val KEY_GOAL = "daily_goal"
        private const val KEY_BASELINE = "counter_baseline"
        private const val BASELINE_UNSET = Long.MIN_VALUE
        private fun todayKey(): String = LocalDate.now().toString()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StepViewModel(application) as T
    }
}

@Composable
private fun StrideAITheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Composable
fun StrideApp(
    state: StepUiState,
    onRequestPermission: () -> Unit,
    onApplyAiGoal: () -> Unit
) {
    Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(Backdrop)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Header()
                if (!state.permissionGranted) {
                    PermissionCard(onRequestPermission)
                    Spacer(Modifier.height(16.dp))
                } else if (!state.hasStepSensor) {
                    SensorUnavailableCard()
                    Spacer(Modifier.height(16.dp))
                }
                StepOrb(state)
                Spacer(Modifier.height(20.dp))
                StatRow(state)
                Spacer(Modifier.height(22.dp))
                AiCoachCard(state, onApplyAiGoal)
                Spacer(Modifier.height(18.dp))
                WeekCard(state.week)
                Spacer(Modifier.height(22.dp))
                BottomStatus(state)
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

private val Backdrop: Brush = Brush.verticalGradient(
    listOf(Color(0xFF11102C), Ink, Color(0xFF07151F))
)

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("STRIDE", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Text("AI MOTION LAB", color = Neon, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
        Surface(
            color = Color(0x1A76F7E9),
            shape = RoundedCornerShape(50),
            modifier = Modifier.drawBehind {
                drawCircle(color = Neon.copy(alpha = .45f), style = Stroke(width = 1.dp.toPx()))
            }
        ) {
            Text("●  LIVE", color = Neon, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun StepOrb(state: StepUiState) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "stepProgress"
    )
    val pulseTransition = rememberInfiniteTransition(label = "orbPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = .20f,
        targetValue = .65f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(285.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val canvasSize = size.minDimension
            val arcSize = Size(canvasSize - stroke, canvasSize - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawCircle(Neon.copy(alpha = .045f + pulse * .05f), radius = canvasSize * (.44f + pulse * .04f))
            drawCircle(Violet.copy(alpha = .07f), radius = canvasSize * .37f)
            drawArc(
                color = Color(0xFF28344E), startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(Neon, Color(0xFF45CFFF), Violet, Neon)),
                startAngle = 135f, sweepAngle = animatedProgress * 270f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
            if (animatedProgress > .01f) {
                val angle = Math.toRadians((135 + animatedProgress * 270).toDouble())
                val radius = (canvasSize - stroke) / 2
                val center = Offset(canvasSize / 2, canvasSize / 2)
                val marker = Offset(
                    center.x + (kotlin.math.cos(angle) * radius).toFloat(),
                    center.y + (kotlin.math.sin(angle) * radius).toFloat()
                )
                drawCircle(Color.White, radius = 5.dp.toPx(), center = marker)
                drawCircle(Neon.copy(alpha = .35f), radius = 12.dp.toPx(), center = marker)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("आज के कदम", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("${state.steps}", color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp)
            Text("/ ${state.goal} लक्ष्य", color = Neon, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text("${(state.progress * 100).roundToInt()}% COMPLETE", color = Color(0xFFBFCBE0), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        }
    }
}

@Composable
private fun StatRow(state: StepUiState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard("↗", "${"%.2f".format(state.distanceKm)}", "KM", Neon, Modifier.weight(1f))
        MetricCard("♨", "${state.calories}", "KCAL", Orange, Modifier.weight(1f))
        MetricCard("◷", "${state.activeMinutes}", "MIN", Violet, Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(icon: String, value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        color = Panel.copy(alpha = .86f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.height(104.dp).drawBehind {
            drawRoundRect(PanelEdge.copy(alpha = .7f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()), style = Stroke(1.dp.toPx()))
        }
    ) {
        Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(icon, color = accent, fontSize = 18.sp)
            Column {
                Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
                Text(label, color = Muted, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = .8.sp)
            }
        }
    }
}

@Composable
private fun AiCoachCard(state: StepUiState, onApplyAiGoal: () -> Unit) {
    val suggestion = aiSuggestion(state)
    Surface(
        color = Color(0xFF151432),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().drawBehind {
            drawRoundRect(
                brush = Brush.linearGradient(listOf(Violet.copy(alpha = .9f), Neon.copy(alpha = .55f))),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()),
                style = Stroke(1.dp.toPx())
            )
        }
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Violet.copy(alpha = .20f), shape = RoundedCornerShape(12.dp)) {
                    Text("✦", color = Color(0xFFC5BEFF), fontSize = 22.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("AI COACH", color = Color(0xFFC5BEFF), fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                    Text("आपकी daily motion intelligence", color = Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(15.dp))
            Text(suggestion.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(suggestion.body, color = Color(0xFFD6DCF0), fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("सुझाया लक्ष्य  ${suggestion.goal} कदम", color = Neon, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Button(
                    onClick = onApplyAiGoal,
                    colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 13.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp)
                ) { Text("अपनाएँ", fontSize = 12.sp, fontWeight = FontWeight.Black) }
            }
        }
    }
}

private data class AiInsight(val title: String, val body: String, val goal: Int)

private fun aiSuggestion(state: StepUiState): AiInsight {
    val recordedDays = state.week.map { it.steps }.filter { it > 0 }
    val average = if (recordedDays.isEmpty()) 6_000 else recordedDays.average().roundToInt()
    val suggestedGoal = ((average * 1.12).roundToInt().coerceIn(4_000, 12_000) / 500) * 500
    return when {
        state.steps == 0 -> AiInsight("आज की momentum बनाइए", "10 मिनट की हल्की walk से शुरू कीजिए। आपका coach आज के लिए एक आसान rhythm सुझा रहा है।", suggestedGoal)
        state.progress < .45f -> AiInsight("छोटा walk, बड़ा फर्क", "आप target के ${(state.progress * 100).roundToInt()}% पर हैं। अगले 20 मिनट में ${max(500, (state.goal - state.steps) / 3)} कदम का mini-walk सही रहेगा।", suggestedGoal)
        state.progress < 1f -> AiInsight("आप strong pace पर हैं", "लक्ष्य तक सिर्फ ${state.goal - state.steps} कदम बाकी हैं। एक short walk आपके daily streak को पूरा कर देगी।", suggestedGoal)
        else -> AiInsight("लक्ष्य पार कर लिया!", "बहुत बढ़िया। Recovery के लिए पानी पिएँ और 2 मिनट की calf stretch जोड़ें—आपका body कल भी तैयार रहेगा।", suggestedGoal)
    }
}

@Composable
private fun WeekCard(week: List<DayPoint>) {
    val maxSteps = max(1, week.maxOfOrNull { it.steps } ?: 1)
    Surface(color = Panel.copy(alpha = .88f), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("WEEKLY PULSE", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("आपकी 7 दिन की चाल", color = Muted, fontSize = 12.sp)
                }
                Text("⌁", color = Neon, fontSize = 25.sp)
            }
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                week.forEachIndexed { index, day ->
                    val fraction = if (day.steps == 0) .09f else (day.steps.toFloat() / maxSteps).coerceAtLeast(.16f)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Box(
                            modifier = Modifier
                                .width(25.dp)
                                .height((78 * fraction).dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (index == week.lastIndex) Brush.verticalGradient(listOf(Neon, Color(0xFF38A8F4))) else Brush.verticalGradient(listOf(Color(0xFF5D5A9B), Color(0xFF303A62))))
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(day.label, color = if (index == week.lastIndex) Neon else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Surface(color = Orange.copy(alpha = .12f), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⚡", fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text("Live steps के लिए Motion permission दें", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink), modifier = Modifier.height(32.dp)) {
                Text("Enable", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SensorUnavailableCard() {
    Surface(color = Color(0x22FFB86B), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Text("इस device में step sensor नहीं मिला। किसी step-sensor वाले phone पर live tracking उपलब्ध होगी।", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun BottomStatus(state: StepUiState) {
    val source = if (state.usesStepCounter) "Hardware sensor connected" else "Step detector connected"
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = if (state.permissionGranted && state.hasStepSensor) Neon else Orange, fontSize = 10.sp)
        Spacer(Modifier.width(6.dp))
        Text(if (state.permissionGranted && state.hasStepSensor) source else "Tracking needs attention", color = Muted, fontSize = 11.sp)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050712)
@Composable
private fun StridePreview() {
    StrideAITheme {
        StrideApp(
            state = StepUiState(steps = 5_480, permissionGranted = true, week = listOf(
                DayPoint("M", 4800), DayPoint("T", 6200), DayPoint("W", 3800), DayPoint("T", 7100), DayPoint("F", 5400), DayPoint("S", 3100), DayPoint("S", 5480)
            )),
            onRequestPermission = {},
            onApplyAiGoal = {}
        )
    }
}
