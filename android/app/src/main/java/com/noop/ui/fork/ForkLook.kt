package com.noop.ui.fork

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.noop.R
import com.noop.ui.DarkTokens
import com.noop.ui.LightTokens
import com.noop.ui.NoopType
import com.noop.ui.Palette
import com.noop.ui.PaletteTokens
import com.noop.ui.SegmentedPillControl
import com.noop.ui.SettingsFormRow
import com.noop.ui.SettingsRowDivider
import androidx.compose.material3.Text

/**
 * Fork-only "Graphite" look: neutral graphite surfaces in place of the blue-navy canvas, a cool white light
 * theme in place of warm paper, and the Inter typeface. Default off.
 *
 * Kept out of upstream's files so a rebase stays cheap. Upstream code reaches this in five one-line hooks:
 * the token pick in `NoopTheme`, `Palette.isLight`, `NoopType`'s sans family, [load] in `MainActivity`, and
 * [SettingsRow] in the Appearance section. Everything else lives here.
 *
 * Only chrome changes. The palette is a `copy` of upstream's tokens with surfaces, lines and text replaced, so
 * every data colour (recovery, strain, sleep stages, zones, domain worlds) and the user's accent stay
 * upstream's, and a token upstream adds later is inherited with its upstream value. The palette switches
 * live; the typeface is read once when [NoopType] initialises, so it applies from the next launch.
 *
 * Stored in its own preferences file, so it never enters the `.noopbak` whitelist or upstream's prefs keys.
 */
object ForkLook {
    private const val FILE = "noop_fork_prefs"
    private const val KEY = "look.graphite"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Live toggle read by NoopTheme. */
    var enabled by mutableStateOf(false)
        private set

    /** The toggle as it stood when [load] first ran; the typeface follows this, not [enabled]. */
    private var enabledAtLaunch = false
    private var loaded = false

    /** Call before first composition, beside the other appearance prefs. */
    fun load(ctx: Context) {
        enabled = prefs(ctx).getBoolean(KEY, false)
        if (!loaded) {
            loaded = true
            enabledAtLaunch = enabled
        }
    }

    fun set(ctx: Context, value: Boolean) {
        enabled = value
        prefs(ctx).edit().putBoolean(KEY, value).apply()
    }

    val dark: PaletteTokens = DarkTokens.copy(
        surfaceBase = Color(0xFF0E0F11), surfaceRaised = Color(0xFF1A1C1F), surfaceOverlay = Color(0xFF16181B),
        surfaceInset = Color(0xFF131417), hairline = Color(0xFF26292D), hairlineStrong = Color(0xFF34383D),
        textPrimary = Color(0xFFF5F6F7), textSecondary = Color(0xFFB6BCC4), textTertiary = Color(0xFF858C96),
        scenicCenter = Color(0xFF16181B), scenicEdge = Color(0xFF0E0F11),
        cardFillTop = Color(0xFF1A1C1F), cardFillBottom = Color(0xFF15171A),
        heroFill = Color(0xE6131417), heroBorder = Color(0x14FFFFFF),
    )

    val light: PaletteTokens = LightTokens.copy(
        surfaceBase = Color(0xFFF3F4F6), surfaceRaised = Color(0xFFFFFFFF), surfaceOverlay = Color(0xFFFFFFFF),
        surfaceInset = Color(0xFFEBEDF0), hairline = Color(0xFFE1E4E8), hairlineStrong = Color(0xFFCCD1D7),
        textPrimary = Color(0xFF111418), textSecondary = Color(0xFF4A525C), textTertiary = Color(0xFF6E7681),
        glowAmbient = Color(0xFFE8ECF2),
        scenicCenter = Color(0xFFFFFFFF), scenicEdge = Color(0xFFF0F2F5), scenicStar = Color(0xFFD5DAE0),
        cardFillTop = Color(0xFFFFFFFF), cardFillBottom = Color(0xFFFAFBFC),
    )

    /** The token set NoopTheme installs: this look's when on, upstream's otherwise. */
    fun tokens(isDark: Boolean): PaletteTokens = when {
        enabled -> if (isDark) dark else light
        else -> if (isDark) DarkTokens else LightTokens
    }

    private val inter = FontFamily(
        Font(R.font.fork_inter_regular, FontWeight.Normal),
        Font(R.font.fork_inter_medium, FontWeight.Medium),
        Font(R.font.fork_inter_semibold, FontWeight.SemiBold),
        Font(R.font.fork_inter_bold, FontWeight.Bold),
    )

    /** The house sans for [NoopType]: Inter when the look was on at launch, the platform sans otherwise. */
    val sansFamily: FontFamily get() = if (enabledAtLaunch) inter else FontFamily.SansSerif

    /** The Appearance row: a divider and an Off / On control, with a note when the typeface awaits a relaunch. */
    @Composable
    fun SettingsRow() {
        val context = LocalContext.current
        SettingsRowDivider()
        SettingsFormRow(label = stringResource(R.string.fork_look_graphite)) {
            SegmentedPillControl(
                items = listOf(false, true),
                selection = enabled,
                label = {
                    if (it) context.getString(R.string.fork_look_on) else context.getString(R.string.fork_look_off)
                },
                onSelect = { set(context, it) },
            )
        }
        if (enabled != enabledAtLaunch) {
            Text(
                stringResource(R.string.fork_look_relaunch),
                style = NoopType.caption,
                color = Palette.textTertiary,
            )
        }
    }
}
