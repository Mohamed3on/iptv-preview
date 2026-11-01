@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mohamed3on.iptv.diag

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.Radius
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.edgeFade
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.tv.material3.Text
import kotlin.math.abs

private const val TAG = "IptvDiag"

/** Hardware facts the plan depends on: decoder instances (tile cap), audio decoders, display modes. */
fun diagnosticsReport(context: Context): List<String> = buildList {
    add("== Device ==")
    add("Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
    add("Fire OS: ${systemProperty("ro.build.version.name") ?: "unknown"}")
    add("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")

    val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
    fun decodersFor(mime: String) = codecs.filter { info -> info.supportedTypes.any { it.equals(mime, true) } }

    for (mime in listOf("video/avc", "video/hevc")) {
        add("== Video decoders: $mime ==")
        val found = decodersFor(mime)
        if (found.isEmpty()) add("none")
        for (info in found) {
            val caps = info.getCapabilitiesForType(mime)
            val video = caps.videoCapabilities!!
            val kind = if (info.isHardwareAccelerated) "hardware" else "software"
            val fps1080 = runCatching { video.getSupportedFrameRatesFor(1920, 1080).upper }.getOrNull()
            add("${info.name}: $kind, maxInstances=${caps.maxSupportedInstances}, " +
                "max ${video.supportedWidths.upper}x${video.supportedHeights.upper}, " +
                "1080p max fps=${fps1080?.let { "%.0f".format(it) } ?: "unsupported"}, " +
                "1080p50=${video.areSizeAndRateSupported(1920, 1080, 50.0)}")
        }
    }

    add("== Audio decoders ==")
    for (mime in listOf("audio/mpeg-L2", "audio/mpeg", "audio/mp4a-latm", "audio/ac3", "audio/eac3", "audio/eac3-joc")) {
        val names = decodersFor(mime).map { it.name }
        add("$mime: ${names.joinToString().ifEmpty { "none" }}")
    }

    add("== HDMI audio passthrough ==")
    val audioCaps = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null, emptyList())
    val encodings = listOf(
        "AC3" to C.ENCODING_AC3, "E-AC3" to C.ENCODING_E_AC3, "E-AC3-JOC" to C.ENCODING_E_AC3_JOC,
        "DTS" to C.ENCODING_DTS, "DTS-HD" to C.ENCODING_DTS_HD, "TrueHD" to C.ENCODING_DOLBY_TRUEHD,
    )
    add("Media3: max channels ${audioCaps.maxChannelCount}, passthrough " +
        encodings.filter { audioCaps.supportsEncoding(it.second) }.joinToString { it.first }.ifEmpty { "none" })
    val audioManager = context.getSystemService(AudioManager::class.java)
    for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
        add("Output type=${device.type} '${device.productName}': encodings=${device.encodings.toList()}, " +
            "channels=${device.channelCounts.toList()}")
    }

    add("== Display ==")
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val current = display.mode
    add("Current: ${current.describe()}")
    add("Alternative refresh rates: " + if (Build.VERSION.SDK_INT >= 31) {
        current.alternativeRefreshRates.joinToString { "%.3f".format(it) }.ifEmpty { "none" }
    } else "n/a (needs API 31)")
    add("Supported modes:")
    display.supportedModes.forEach { add("  ${it.describe()}") }
    val modes50 = display.supportedModes.filter { abs(it.refreshRate - 50f) < 0.5f }
    add("50Hz modes: ${modes50.joinToString { it.describe() }.ifEmpty { "none" }}")
}

private fun Display.Mode.describe() = "#$modeId ${physicalWidth}x$physicalHeight@${"%.3f".format(refreshRate)}"

private fun systemProperty(key: String): String? = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        .invoke(null, key) as String
}.getOrNull()?.ifEmpty { null }

@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val lines = remember { diagnosticsReport(context) }
    LaunchedEffect(lines) { lines.forEach { Log.i(TAG, it) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Palette.ink).edgeFade(),
        contentPadding = Space.safe,
    ) {
        item { Text("Diagnostics", style = Type.title, modifier = Modifier.padding(bottom = Space.l)) }
        items(lines) { line -> ReportLine(line) }
    }
}

// Each line takes focus so the D-pad moves through the report and the list follows it.
@Composable
private fun ReportLine(line: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val header = line.startsWith("==")
    Text(
        text = if (header) line.trim('=', ' ') else line,
        style = if (header) Type.headline else Type.body.copy(color = if (focused) Palette.textPrimary else Palette.textSecondary),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (header) Space.xl else 0.dp)
            .clip(RoundedCornerShape(Radius.chip))
            .background(if (focused) Palette.raised else Color.Transparent)
            .focusable(interactionSource = interaction)
            .padding(vertical = if (header) Space.xs else 2.dp, horizontal = Space.s),
    )
}
