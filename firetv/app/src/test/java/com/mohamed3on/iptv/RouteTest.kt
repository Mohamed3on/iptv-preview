package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.ProviderRoute
import com.mohamed3on.iptv.data.Route
import com.mohamed3on.iptv.data.RouteChoice
import com.mohamed3on.iptv.data.RouteKind
import com.mohamed3on.iptv.data.streamUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteTest {
    private val provider = ProviderRoute(plain = "http://plain.test:8080", vpn = "http://vpn.test", user = "u", pass = "p")

    private fun route(vpnOn: Boolean, store: MapStore = MapStore(), block: (Route, MutableStateFlow<Boolean>) -> Unit) {
        val vpn = MutableStateFlow(vpnOn)
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            block(Route(store, vpn, scope), vpn)
        } finally {
            scope.cancel()
        }
    }

    private fun Route.url() = provider.streamUrl(effective.value, 950400)

    @Test fun vpnOnWithNoChoiceUsesTheVpnHost() = route(vpnOn = true) { r, _ ->
        assertEquals("http://vpn.test/live/u/p/950400.ts", r.url())
    }

    @Test fun vpnOnWithPlainChosenUsesThePlainHost() = route(vpnOn = true) { r, _ ->
        r.choose(RouteChoice.Plain)
        assertEquals("http://plain.test:8080/live/u/p/950400.ts", r.url())
    }

    @Test fun vpnOffWithNoChoiceUsesThePlainHost() = route(vpnOn = false) { r, _ ->
        assertEquals("http://plain.test:8080/live/u/p/950400.ts", r.url())
    }

    @Test fun autoFollowsTheVpnStateAsItChanges() = route(vpnOn = false) { r, vpn ->
        vpn.value = true
        assertEquals(RouteKind.Vpn, r.effective.value)
        vpn.value = false
        assertEquals(RouteKind.Plain, r.effective.value)
    }

    @Test fun theChoiceIsPersisted() {
        val store = MapStore()
        route(vpnOn = true, store) { r, _ -> r.choose(RouteChoice.Plain) }
        route(vpnOn = true, store) { r, _ ->
            assertEquals(RouteChoice.Plain, r.choice.value)
            assertEquals(RouteKind.Plain, r.effective.value)
        }
    }

    // AE5: VPN on, plain forced; the one-press switch moves to VPN for this session only.
    @Test fun aSessionOverrideWinsAndIsNotPersisted() {
        val store = MapStore()
        route(vpnOn = true, store) { r, _ ->
            r.choose(RouteChoice.Plain)
            r.overrideForSession(RouteKind.Vpn)
            assertEquals("http://vpn.test/live/u/p/950400.ts", r.url())
            assertEquals(RouteChoice.Plain, r.choice.value)
        }
        route(vpnOn = true, store) { r, _ ->
            assertEquals(RouteKind.Plain, r.effective.value)
        }
    }
}
