package com.juani.videoshrink

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Screen() } }
    }
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BB0FF),
    onPrimary = Color(0xFF0F1A4A),
    background = Color(0xFF10151E),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF19202C),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF232C3B),
    onSurfaceVariant = Color(0xFFA9B3C6),
    outline = Color(0xFF4A566B),
    secondaryContainer = Color(0xFF2B3A66),
    onSecondaryContainer = Color(0xFFDDE3FF)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B4FD8),
    onPrimary = Color.White,
    background = Color(0xFFEDF0F5),
    onBackground = Color(0xFF151D2B),
    surface = Color.White,
    onSurface = Color(0xFF151D2B),
    surfaceVariant = Color(0xFFE1E6F0),
    onSurfaceVariant = Color(0xFF4A566B),
    outline = Color(0xFFB4BDCE),
    secondaryContainer = Color(0xFFDDE3FF),
    onSecondaryContainer = Color(0xFF1A2670)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}

@Composable
fun Screen(vm: CompressViewModel = viewModel()) {
    val ctx = LocalContext.current
    val view = LocalView.current
    SideEffect { view.keepScreenOn = vm.running }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) vm.addVideos(uris)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.start()
    }

    fun startWithPermission() {
        val needs = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    val pending = vm.jobs.count { it.status == Status.PENDING }
    val saved = vm.jobs.filter { it.status == Status.DONE }.sumOf { it.info.sizeBytes - it.outBytes }
    val doneCount = vm.jobs.count { it.status == Status.DONE }
    val finished = vm.jobs.any { it.status != Status.PENDING && it.status != Status.WORKING }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (doneCount > 0) {
                        Text(
                            "Ahorraste ${fmtSize(saved)} en $doneCount ${if (doneCount == 1) "video" else "videos"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Button(
                        onClick = { if (vm.running) vm.cancel() else startWithPermission() },
                        enabled = vm.running || pending > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text(
                            when {
                                vm.running -> "Cancelar"
                                pending > 0 -> "Comprimir $pending ${if (pending == 1) "video" else "videos"}"
                                else -> "Elegí videos para empezar"
                            }
                        )
                    }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("VideoShrink", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Achica el peso de tus videos cuidando la calidad.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item { Text("Calidad", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(Quality.entries.toList()) { q ->
                QualityCard(q, vm.quality == q, !vm.running) { vm.quality = q }
            }
            item { Text("Resolución", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaxRes.entries.forEach { r ->
                        FilterChip(
                            selected = vm.maxRes == r,
                            onClick = { vm.maxRes = r },
                            enabled = !vm.running,
                            label = { Text(r.label) }
                        )
                    }
                }
            }
            item {
                FilledTonalButton(
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) { Text("Elegir videos") }
            }
            if (vm.jobs.isEmpty()) {
                item {
                    Text(
                        "Todavía no elegiste ningún video. Los comprimidos se guardan en Películas/VideoShrink y los originales no se tocan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(vm.jobs, key = { it.id }) { job ->
                    JobCard(job, vm.quality, vm.maxRes) { vm.remove(job.id) }
                }
                if (finished) {
                    item { TextButton(onClick = { vm.clearFinished() }) { Text("Limpiar los terminados") } }
                }
            }
        }
    }
}

@Composable
private fun QualityCard(q: Quality, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
            Column {
                Text(q.label, fontWeight = FontWeight.SemiBold)
                Text(
                    q.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun JobCard(job: Job, quality: Quality, maxRes: MaxRes, onRemove: () -> Unit) {
    val info = job.info
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(info.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${info.width}×${info.height}, ${info.fps.roundToInt()} fps, ${fmtDuration(info.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (job.status != Status.WORKING) {
                    TextButton(onClick = onRemove) { Text("Quitar") }
                }
            }
            when (job.status) {
                Status.PENDING -> {
                    val plan = Planner.plan(info, quality, maxRes)
                    if (plan == null) {
                        Text(
                            "Ya está optimizado, lo voy a omitir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val pct = (100 - plan.estimatedBytes * 100 / info.sizeBytes).toInt()
                        SizeBars(info.sizeBytes, plan.estimatedBytes, "Quedaría en unos ${fmtSize(plan.estimatedBytes)} (−$pct%)")
                    }
                }
                Status.WORKING -> {
                    LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Comprimiendo… ${(job.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Status.DONE -> {
                    val pct = (100 - job.outBytes * 100 / info.sizeBytes).toInt()
                    SizeBars(info.sizeBytes, job.outBytes, "Ahora pesa ${fmtSize(job.outBytes)} (−$pct%)")
                }
                Status.SKIPPED, Status.ERROR -> {
                    Text(
                        job.message ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Dos barras proporcionales: lo que pesaba vs. lo que pesa (o pesaría). */
@Composable
private fun SizeBars(original: Long, result: Long, resultLabel: String) {
    val frac = (result.toFloat() / original.toFloat()).coerceIn(0.03f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BarRow(1f, MaterialTheme.colorScheme.outline, "Original, ${fmtSize(original)}")
        BarRow(frac, MaterialTheme.colorScheme.primary, resultLabel)
    }
}

@Composable
private fun BarRow(frac: Float, color: Color, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}
