package com.pdm.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdm.app.R
import com.pdm.app.data.api.ApiActivity

/** Orbitron (SIL OFL 1.1, assets/licenses/Orbitron-OFL.txt): protocol tags only, never body text. */
val OrbitronFamily = FontFamily(
    Font(R.font.orbitron_medium, FontWeight.Medium),
    Font(R.font.orbitron_bold, FontWeight.Bold),
)

/** What a protocol token is; the family sets its colour everywhere in the app. */
enum class ProtocolFamily {
    /** Encrypted transport: HTTPS, TLS 1.2+. Cyan. */
    SECURE,

    /** Addressing and segmentation: IPv4, IPv6, VLAN, VXLAN, EVPN. Amber. */
    ADDRESS,

    /** Reads and good answers: GET, 2xx. Green. */
    READ,

    /** Changes: POST, PUT, DELETE. Magenta. */
    WRITE,

    /** Plain, outdated or failed: HTTP, old TLS, 4xx/5xx, no response. Red. */
    BROKEN,

    /** Not an outcome yet, or abandoned: in flight, cancelled. Grey. */
    QUIET,
}

/** A protocol token as tagged: ASCII text only (the bundled font is a Latin subset) and its family. */
data class Protocol(val label: String, val family: ProtocolFamily)

object Protocols {
    val HTTPS = Protocol("HTTPS", ProtocolFamily.SECURE)

    /** OkHttp's `TLSv1.3` as `TLS 1.3`. Anything before 1.2 is flagged. */
    fun tls(javaName: String): Protocol {
        val ssl = javaName.startsWith("SSL")
        val version = javaName.removePrefix("TLSv").removePrefix("SSLv")
        val modern = javaName == "TLSv1.2" || javaName == "TLSv1.3"
        return Protocol(if (ssl) "SSL $version" else "TLS $version", if (modern) ProtocolFamily.SECURE else ProtocolFamily.BROKEN)
    }

    fun method(method: String): Protocol {
        val upper = method.uppercase()
        return Protocol(upper, if (upper == "GET" || upper == "HEAD") ProtocolFamily.READ else ProtocolFamily.WRITE)
    }

    /** A request's outcome: the status code alone, or what happened instead. */
    fun outcome(event: ApiActivity): Protocol {
        val code = event.httpCode
        return when {
            event.cancelled -> Protocol("CANCELLED", ProtocolFamily.QUIET)
            event.networkError -> Protocol("NO RESPONSE", ProtocolFamily.BROKEN)
            code == null -> Protocol("...", ProtocolFamily.QUIET)
            code in 200..299 -> Protocol("$code", ProtocolFamily.READ)
            code in 300..399 -> Protocol("$code", ProtocolFamily.QUIET)
            else -> Protocol("$code", ProtocolFamily.BROKEN)
        }
    }

    fun ip(v6: Boolean) = Protocol(if (v6) "IPv6" else "IPv4", ProtocolFamily.ADDRESS)

    fun vlan(id: String?) = Protocol(if (id.isNullOrBlank()) "VLAN" else "VLAN $id", ProtocolFamily.ADDRESS)

    /** An SDN zone type when it names a protocol; `simple` zones do not. */
    fun zone(type: String?): Protocol? = when (type?.lowercase()) {
        "vxlan" -> Protocol("VXLAN", ProtocolFamily.ADDRESS)
        "evpn" -> Protocol("EVPN", ProtocolFamily.ADDRESS)
        "vlan" -> vlan(null)
        "qinq" -> Protocol("QinQ", ProtocolFamily.ADDRESS)
        else -> null
    }

    /** VLAN id of an interface: PVE's `vlan-id`, else the `.20` / `vlan20` naming conventions. */
    fun vlanId(iface: String, declared: String?): String? =
        declared?.takeIf { it.isNotBlank() }
            ?: Regex("""(?:\.|^vlan)(\d{1,4})$""").find(iface)?.groupValues?.get(1)
}

@Composable
fun protocolTint(family: ProtocolFamily): Color = when (family) {
    ProtocolFamily.SECURE -> TechColors.Cyan
    ProtocolFamily.ADDRESS -> TechColors.Amber
    ProtocolFamily.READ -> TechColors.LinkGreen
    ProtocolFamily.WRITE -> TechColors.Magenta
    ProtocolFamily.BROKEN -> TechColors.Danger
    ProtocolFamily.QUIET -> TechColors.Mute
}

private val ProtocolTagShape = CutCornerShape(bottomEnd = 6.dp)

/**
 * The holo tag: an outlined cut-corner chip with faint scanlines and a glow on the text, in
 * Orbitron. The one way a protocol appears anywhere in the app.
 */
@Composable
fun ProtocolTag(protocol: Protocol, modifier: Modifier = Modifier, large: Boolean = false) {
    val tint = protocolTint(protocol.family)
    val textSize = if (large) 12.sp else 10.sp
    Box(
        modifier
            .clip(ProtocolTagShape)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.14f), tint.copy(alpha = 0.03f))))
            .drawBehind {
                val step = 3.dp.toPx()
                var y = step
                while (y < size.height) {
                    drawLine(tint.copy(alpha = 0.10f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }
            }
            .border(1.dp, tint.copy(alpha = 0.85f), ProtocolTagShape)
            .padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Text(
            protocol.label,
            maxLines = 1,
            color = tint,
            style = TextStyle(
                fontFamily = OrbitronFamily,
                fontWeight = FontWeight.Bold,
                fontSize = textSize,
                lineHeight = textSize * 1.15f,
                letterSpacing = 1.2.sp,
                shadow = Shadow(color = tint.copy(alpha = 0.8f * TechColors.Glow), offset = Offset.Zero, blurRadius = 10f),
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}
