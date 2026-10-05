package com.vanu.collagevideo

import com.vanu.faceswap.core.*

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

private fun mmss(s: Double): String { val t = s.toInt(); return "%d:%02d".format(t / 60, t % 60) }

@Composable
private fun Thumb(b: Bitmap?, size: Int = 64, selected: Boolean = false, dim: Boolean = false, onClick: (() -> Unit)? = null) {
    val m = Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    Box(m, contentAlignment = Alignment.Center) {
        if (b != null) { val ib = remember(b) { b.asImageBitmap() }
            Image(ib, null, contentScale = ContentScale.Crop, alpha = if (dim) 0.35f else 1f, modifier = Modifier.fillMaxSize()) }
        else Text("–", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Hint(t: String) = Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollageContent(vm: CollageViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val running = job is JobState.Running

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.pickVideo(it) } }
    val pickCollage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.pickCollage(it) } }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val d = job as? JobState.Done
        if (granted && d != null) vm.save(d.result.file) else if (!granted) vm.showError("Storage permission is needed to save on this Android version.")
    }
    fun start() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }
    LaunchedEffect(s.toast) { s.toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.toastShown() } }

    Text("Pick a collage or group photo and a video to copy the movement from. The people in the photo become the " +
        "people in the video: their faces, hair, skin tone and outfit colours, moving like the people in the video.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PhotoSlot(title = "Collage", subtitle = "The people", image = s.collage, enabled = !running, modifier = Modifier.weight(1f),
            onGallery = { pickCollage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        Card(modifier = Modifier.weight(1f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Video", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Movement & scene", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !running) { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    contentAlignment = Alignment.Center) {
                    val t = s.thumb
                    if (t != null) {
                        val b = remember(t) { t.asImageBitmap() }
                        Image(b, contentDescription = "Video", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.6f)))
                    } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Tap to pick", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, enabled = !running) { Text("Gallery") }
            }
        }
    }
    s.loading?.let { Column(Modifier.fillMaxWidth()) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, style = MaterialTheme.typography.bodySmall) } }

    if (s.faceThumbs.isNotEmpty()) Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("People in the collage (${s.enabled.size} of ${s.faceThumbs.size} on)", style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.faceThumbs.forEachIndexed { i, b ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Thumb(b, 60, selected = i in s.enabled, dim = i !in s.enabled) { if (!running) vm.toggleFace(i) }
                        Text("${i + 1}" + if (i in s.smallFaces) " · small" else "", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Hint("Numbered in reading order. They go to the people in the video left to right. Tap a face to leave it out.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reuse faces", style = MaterialTheme.typography.bodyMedium)
                    Hint("If the video has more people than the collage, use the collage people again.")
                }
                Switch(checked = s.repeat, onCheckedChange = { vm.setRepeat(it) }, enabled = !running)
            }
        }
    }

    val info = s.info
    if (info != null) {
        val (pw, ph, _) = VideoPlan.outSize(info.width, info.height)
        Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("${mmss(info.seconds)} · ${info.width}×${info.height} · ${"%.0f".format(info.fps)} fps" +
                    (if (info.hasAudio) " · sound" else " · no sound") +
                    (if (pw != info.width || ph != info.height) "  →  made at ${pw}×$ph" else ""), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text("Part to use: ${mmss(s.startS.toDouble())} – ${mmss(s.endS.toDouble())} (${"%.1f".format(s.endS - s.startS)} s, max 30 s)",
                    style = MaterialTheme.typography.bodyMedium)
                RangeSlider(value = s.startS..s.endS, onValueChange = { r -> vm.setRange(r.start, r.endInclusive) },
                    valueRange = 0f..info.seconds.toFloat().coerceAtLeast(0.5f), enabled = !running)
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Text("Take from the collage", style = MaterialTheme.typography.bodyLarge)
                val t = s.transfer
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = t.face, onClick = { vm.setTransfer(t.copy(face = !t.face)) }, label = { Text("Face") }, enabled = !running)
                    FilterChip(selected = t.hair, onClick = { vm.setTransfer(t.copy(hair = !t.hair)) }, label = { Text("Hair colour") }, enabled = !running)
                    FilterChip(selected = t.skin, onClick = { vm.setTransfer(t.copy(skin = !t.skin)) }, label = { Text("Skin tone") }, enabled = !running)
                    FilterChip(selected = t.outfit, onClick = { vm.setTransfer(t.copy(outfit = !t.outfit)) }, label = { Text("Outfit colours") }, enabled = !running)
                }
                Hint("Body shape, hairstyle, clothing cut and the movement come from the video. Colours are matched to the video's light. " +
                    "Clothes that the collage doesn't show (e.g. trousers in a head-and-shoulders photo) are kept.")
                Spacer(Modifier.height(6.dp))
                Text("Frame rate", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((f, label) in listOf(10 to "10 fps", 15 to "15 fps", 0 to "Original")) {
                        FilterChip(selected = s.fps == f, onClick = { vm.setFps(f) }, label = { Text(label) }, enabled = !running)
                    }
                }
                Hint("15 fps halves the work versus 30 fps and still looks smooth; Original keeps every frame (max 30 fps).")
                Spacer(Modifier.height(6.dp))
                Text("Face detail", style = MaterialTheme.typography.bodyLarge)
                val busyDl = s.enhDownloading != null
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = s.enhance == EnhanceMode.OFF, onClick = { vm.setEnhance(EnhanceMode.OFF) }, label = { Text("Off") }, enabled = !running)
                    FilterChip(selected = s.enhance == EnhanceMode.LIGHT, onClick = { if (s.lightInstalled) vm.setEnhance(EnhanceMode.LIGHT) else vm.downloadEnhancer(EnhanceMode.LIGHT) },
                        label = { Text(if (s.lightInstalled) "Light" else "Light ↓") }, enabled = !running && !busyDl)
                    FilterChip(selected = s.enhance == EnhanceMode.HQ, onClick = { if (s.hqInstalled) vm.setEnhance(EnhanceMode.HQ) else vm.downloadEnhancer(EnhanceMode.HQ) },
                        label = { Text(if (s.hqInstalled) "HQ" else "HQ ↓") }, enabled = !running && !busyDl)
                }
                Hint(when (s.enhance) {
                    EnhanceMode.OFF -> "Fastest. Faces are a little soft (the face model works at 128 px)."
                    EnhanceMode.LIGHT -> "Sharper faces for a little more time (GPEN 256). Recommended."
                    EnhanceMode.HQ -> "Sharpest (GPEN 512) but much slower: best for short clips."
                })
                val dl = s.enhDownloading
                if (dl != null) {
                    val spec = if (dl == EnhanceMode.LIGHT) Models.ENHANCER_LIGHT else Models.ENHANCER
                    LinearProgressIndicator(progress = { (s.enhDone.toFloat() / spec.bytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloading ${if (dl == EnhanceMode.LIGHT) "Light" else "HQ"} enhancer: ${s.enhDone / 1_000_000} of ${mb(spec.bytes)}",
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.pauseEnhancer() }) { Text("Pause") }
                    }
                }
                val frames = remember(s.startS, s.endS, s.fps, info) { vm.frameCount() }
                Hint("$frames frames to make (roughly 1–3 s per frame on a recent phone).")
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Text("Safety filter", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in FilterStrictness.values())
                        FilterChip(selected = s.strictness == v, onClick = { vm.setStrictness(v) }, label = { Text(v.label) }, enabled = !running)
                }
                Hint(s.strictness.help + " The filter is always on: it checks the photo and the video, and nothing is made if either is flagged.")
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Experimental accelerator", style = MaterialTheme.typography.bodyMedium)
                        Hint("Try XNNPACK instead of the standard CPU engine. Falls back automatically if unsupported.")
                    }
                    Switch(checked = s.accelerator, onCheckedChange = { vm.setAccelerator(it) }, enabled = !running)
                }
            }
        }
    }

    Button(onClick = { start() }, enabled = !running && s.video != null && s.collageFaces.isNotEmpty() && s.loading == null,
        modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Filled.Face, contentDescription = null); Spacer(Modifier.size(8.dp))
        Text("Create collage video", style = MaterialTheme.typography.titleMedium)
    }

    when (val j = job) {
        is JobState.Running -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(j.stage + "…", style = MaterialTheme.typography.titleSmall)
                if (j.total > 0) LinearProgressIndicator(progress = { j.done.toFloat() / j.total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (j.detail.isNotEmpty()) Text(j.detail, style = MaterialTheme.typography.bodySmall)
                Hint("You can leave the app or turn off the screen. Progress is shown in the notification.")
                OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
        is JobState.Failed -> ErrorCard(j.message, j.detail)
        is JobState.Cancelled -> Text("Cancelled.", style = MaterialTheme.typography.bodySmall)
        is JobState.Done -> ResultCard(vm, s, j, running) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.save(j.result.file)
        }
        JobState.Idle -> {}
    }
    s.error?.let { ErrorCard(it, s.errorDetail) }
}

@Composable
private fun ResultCard(vm: CollageViewModel, s: CollageUi, j: JobState.Done, running: Boolean, onSave: () -> Unit) {
    val ctx = LocalContext.current
    val r = j.result
    Text("Result", style = MaterialTheme.typography.titleMedium)
    ResultPlayer(r.file, r.width, r.height)
    Text(buildString {
        append("${r.frames} frames at ${"%.0f".format(r.fps)} fps, ${r.width}×${r.height}. ")
        append("${r.swappedPeople} of ${r.people.size} people turned into collage people. ")
        append(if (r.audioCopied) "Original sound kept. " else r.audioNote?.let { "$it " } ?: "")
        append("Safety checks passed (${r.safetyChecks}). Done in ${EtaEstimator.format(r.seconds)}.")
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    r.notes.forEach { Hint("• $it") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { vm.shuffle() }, enabled = !running && r.people.size >= 1 && s.enabled.size >= 1, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(4.dp))
            Text(if (s.enabled.size == 2) "Flip" else "Shuffle")
        }
        FilledTonalButton(onClick = onSave, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(4.dp)); Text("Save")
        }
        FilledTonalButton(onClick = {
            try { ctx.startActivity(VideoFiles.shareIntent(ctx, r.file)) }
            catch (e: Exception) { android.util.Log.e(ImageUtils.TAG, "share failed", e); vm.showError("Share failed.", ImageUtils.describe(e)) }
        }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(4.dp)); Text("Share")
        }
    }
    // "Who becomes who": per person in the video, pick which collage person they become (or keep them as they are)
    if (r.people.isNotEmpty() && s.resultVersion == s.inputsVersion) Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Who becomes who", style = MaterialTheme.typography.bodyLarge)
            val cur = s.edit ?: r.people.map { it.face }.toIntArray()
            for (pi in r.order.filter { it in r.people.indices }) {
                val p = r.people[pi]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Thumb(p.before, 52); Text("→", style = MaterialTheme.typography.titleMedium); Thumb(p.after, 52)
                    Spacer(Modifier.width(4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(if (cur[pi] < 0) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable(enabled = !running) { vm.setPersonFace(pi, -1) }, contentAlignment = Alignment.Center) {
                            Text("Keep", style = MaterialTheme.typography.labelSmall) }
                        s.faceThumbs.forEachIndexed { fi, b -> Thumb(b, 40, selected = cur[pi] == fi) { if (!running) vm.setPersonFace(pi, fi) } }
                    }
                }
            }
            if (s.edit != null) Button(onClick = { vm.applyEdit() }, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text("Make it again with these choices") }
            else Hint("Tap a collage face next to a person to change who they become, or Keep to leave them as they are.")
        }
    }
}

/** In-app preview with the platform VideoView (no extra player library, keeps the APK small). */
@Composable
private fun ResultPlayer(file: File, w: Int, h: Int) {
    Box(Modifier.fillMaxWidth().aspectRatio(w.toFloat() / h).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.Black)) {
        AndroidView(factory = { c ->
            VideoView(c).apply {
                val mc = MediaController(c); mc.setAnchorView(this); setMediaController(mc)
                setOnPreparedListener { mp -> mp.isLooping = true; start() }
                setVideoPath(file.absolutePath); tag = file.absolutePath
            }
        }, update = { v -> if (v.tag != file.absolutePath) { v.tag = file.absolutePath; v.setVideoPath(file.absolutePath) } },
            onRelease = { it.stopPlayback() }, modifier = Modifier.fillMaxSize())
    }
}
