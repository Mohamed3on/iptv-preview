package com.mohamed3on.iptv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class RouteKind { Plain, Vpn }

/** The persisted route setting: Auto follows the stick's VPN state (R4). */
enum class RouteChoice { Auto, Plain, Vpn }

/**
 * Which provider front streams use: a session override (the KTD8 one-press switch, never
 * persisted), else the persisted choice, else the VPN state.
 */
class Route(private val store: KeyValueStore, private val vpnActive: StateFlow<Boolean>, scope: CoroutineScope) {
    private val _choice = MutableStateFlow(
        store.get(KEY)?.let { runCatching { RouteChoice.valueOf(it) }.getOrNull() } ?: RouteChoice.Auto
    )
    val choice: StateFlow<RouteChoice> = _choice
    private val session = MutableStateFlow<RouteKind?>(null)
    /** The session override, null when none is set. */
    val override: StateFlow<RouteKind?> = session

    val effective: StateFlow<RouteKind> = combine(session, _choice, vpnActive, ::pick)
        .stateIn(scope, SharingStarted.Eagerly, pick(session.value, _choice.value, vpnActive.value))

    /**
     * Persists an explicit choice; it replaces any session override. Returns the route it resolves
     * to now, since [effective] catches up a moment later.
     */
    fun choose(choice: RouteChoice): RouteKind {
        session.value = null
        _choice.value = choice
        store.put(KEY, choice.name)
        return pick(null, choice, vpnActive.value)
    }

    /** Forces a route until the app process ends, or clears the override with null. */
    fun overrideForSession(kind: RouteKind?) {
        session.value = kind
    }

    private companion object {
        const val KEY = "route.choice"

        fun pick(session: RouteKind?, choice: RouteChoice, vpn: Boolean) = session ?: when (choice) {
            RouteChoice.Plain -> RouteKind.Plain
            RouteChoice.Vpn -> RouteKind.Vpn
            RouteChoice.Auto -> if (vpn) RouteKind.Vpn else RouteKind.Plain
        }
    }
}

fun ProviderRoute.streamUrl(kind: RouteKind, streamId: Int): String =
    "${(if (kind == RouteKind.Vpn) vpn else plain).trimEnd('/')}/live/$user/$pass/$streamId.ts"

/** A movie's stream: the provider's VOD path, with the file's own [ext] (mkv, mp4). */
fun ProviderRoute.movieUrl(kind: RouteKind, movieId: Int, ext: String): String =
    "${(if (kind == RouteKind.Vpn) vpn else plain).trimEnd('/')}/movie/$user/$pass/$movieId.$ext"

/** A show episode's stream: the provider's series path, with the file's own [ext]. */
fun ProviderRoute.episodeUrl(kind: RouteKind, episodeId: Int, ext: String): String =
    "${(if (kind == RouteKind.Vpn) vpn else plain).trimEnd('/')}/series/$user/$pass/$episodeId.$ext"

/** The backup provider's feeds are its own stream ids offset by this (the server's BACKUP_ID). */
const val BACKUP_ID = 1_000_000_000

val Channel.isBackup get() = id >= BACKUP_ID

/** [channel]'s stream on [kind]: the backup provider's front and account for its feeds, else the main one's. */
fun Feed.streamUrl(kind: RouteKind, channel: Channel): String =
    backup?.takeIf { channel.isBackup }?.streamUrl(kind, channel.id - BACKUP_ID) ?: route.streamUrl(kind, channel.id)

/** Whether the stick's default network is a VPN, kept current by a network callback. */
class VpnMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _active = MutableStateFlow(
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    )
    val active: StateFlow<Boolean> = _active

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _active.value = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }

            override fun onLost(network: Network) {
                _active.value = false
            }
        })
    }
}
