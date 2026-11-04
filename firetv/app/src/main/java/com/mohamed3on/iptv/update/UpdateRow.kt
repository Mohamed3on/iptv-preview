package com.mohamed3on.iptv.update

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Text
import com.mohamed3on.iptv.ui.GroupRow
import com.mohamed3on.iptv.ui.groupMotion
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type

/**
 * The overlay's [updateRow][com.mohamed3on.iptv.ui.ChannelOverlay] slot: an accent-marked
 * "Update ready" row once [Updater] holds a verified build, nothing otherwise (R15, AE3).
 */
@Composable
fun updateRow(updater: Updater): LazyListScope.() -> Unit {
    val update = updater.ready.collectAsState().value ?: return {}
    val context = LocalContext.current
    return {
        item(key = "update") {
            GroupRow(
                "Update ready · ${update.versionName}",
                marked = true,
                focus = null,
                onFocused = {},
                onClick = { updater.install(context) },
                modifier = groupMotion(),
            )
        }
        if (update.askedPermission) item(key = "update-hint") {
            Text("Allow installs from this app, then pick again", style = Type.caption, modifier = groupMotion().padding(horizontal = Space.l))
        }
    }
}
