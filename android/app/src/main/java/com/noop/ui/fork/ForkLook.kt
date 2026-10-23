package com.noop.ui.fork

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.Text
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
import com.noop.ui.AppearancePrefs
import com.noop.ui.DarkTokens
import com.noop.ui.GaugeNumeralStyle
import com.noop.ui.LightTokens
import com.noop.ui.NoopPrefs
import com.noop.ui.NoopType
import com.noop.ui.Palette
import com.noop.ui.PaletteTokens
import com.noop.ui.SegmentedPillControl
import com.noop.ui.SettingsFormRow
import com.noop.ui.SettingsRowDivider

/**
 * Fork-only "Slate" look, after a muted health-dashboard reference: a deep slate canvas with raised slate cards,
 * desaturated data colours (sage recovery, terracotta strain, dusty-blue sleep), a lavender-grey accent with
 * light pill buttons, and the Inter typeface. A cool white light variant uses the same muted data colours,
 * deepened for contrast on white. Default off.
 *
 * Kept out of upstream's files so a rebase stays cheap. Upstream code reaches this in seven one-line hooks:
 * the token pick in `NoopTheme`, `Palette.isLight`, `NoopType`'s sans family, [load] in `MainActivity`,
 * [SettingsRow] in the Appearance section, [ringNumeralWeight] in `glowRingCenterTextStyle`, and [tubeTrack] in
 * `LiquidRender`. Everything else lives here.
 *
 * The palettes are `copy`s of upstream's tokens, so a token upstream adds later is inherited with its upstream
 * value until it is themed here. Only colours change; no score, threshold or stored value does. The "Classic"
 * chart style still overrides the data colours when picked. The accent follows these tokens only while the
 * Accent setting is Mint (its default); WHOOP Blue and Custom keep their own colour.
 *
 * Turning the look on also switches the day-cycle backdrop off and gauge numerals to Softer, the two upstream
 * settings the reference depends on (a flat canvas, light-weight numbers). Both stay ordinary settings and are
 * not reverted when the look is turned off. The Settings screen mirrors them in local state, so their rows
 * there show the change after Settings is reopened.
 *
 * The palette switches live; the typeface is read once when [NoopType] initialises, so it applies from the next
 * launch. Stored in its own preferences file, so it never enters the `.noopbak` whitelist or upstream's prefs.
 */
object ForkLook {
    private const val FILE = "noop_fork_prefs"
    private const val KEY = "look.slate"

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
        if (value) {
            NoopPrefs.setShowDayCycleBackground(ctx, false)
            AppearancePrefs.setGaugeNumerals(ctx, GaugeNumeralStyle.SOFT)
        }
    }

    // Sampled from the reference: canvas #232A38, cards #343B4C, dividers #38404F, recovery #8CA697,
    // strain #AA7D6E, sleep #61738C, chart line #7A9197, button #C0C4D0, active tab #C4CAE0, labels #7E838A.
    val dark: PaletteTokens = DarkTokens.copy(
        surfaceBase = Color(0xFF232A38), surfaceRaised = Color(0xFF343B4C), surfaceOverlay = Color(0xFF2C3342),
        surfaceInset = Color(0xFF2A3140), hairline = Color(0xFF3D4555), hairlineStrong = Color(0xFF4A5263),
        textPrimary = Color(0xFFEEF0F4), textSecondary = Color(0xFFB9BEC9), textTertiary = Color(0xFF8A909C),
        glowAmbient = Color(0xFF2E3646),
        accent = Color(0xFFB8C1DE), accentHover = Color(0xFFD0D6EA), accentMuted = Color(0xFF3A4258),
        focusRing = Color(0xFFB8C1DE),
        recovery000 = Color(0xFFB0766A), recovery030 = Color(0xFFB58A6E), recovery055 = Color(0xFFBBA27A),
        recovery078 = Color(0xFF9FB39C), recovery100 = Color(0xFF8CA697),
        strain000 = Color(0xFF7D5E57), strain033 = Color(0xFF8F6A5F), strain066 = Color(0xFFA27667),
        strain100 = Color(0xFFB98A79),
        sleepAwake = Color(0xFFB7BCC6), sleepLight = Color(0xFF7C8FA8), sleepDeep = Color(0xFF55657D),
        sleepREM = Color(0xFF9AA9C4),
        zone1 = Color(0xFF7C8FA8), zone2 = Color(0xFF7A9197), zone3 = Color(0xFFC2A274), zone4 = Color(0xFFB98A79),
        zone5 = Color(0xFFB5776A),
        statusPositive = Color(0xFF8CA697), statusWarning = Color(0xFFC2A274), statusCritical = Color(0xFFB5776A),
        metricCyan = Color(0xFF7A9197), metricPurple = Color(0xFF8C96B8), metricAmber = Color(0xFFC2A274),
        metricRose = Color(0xFFB5776A),
        chargeColor = Color(0xFF8CA697), chargeDeep = Color(0xFF6E8579), chargeBright = Color(0xFFA9C0B2),
        chargeGlow = Color(0xFF8CA697),
        effortColor = Color(0xFFAA7D6E), effortDeep = Color(0xFF8B655F), effortBright = Color(0xFFC49585),
        effortGlow = Color(0xFFAA7D6E),
        restColor = Color(0xFF61738C), restDeep = Color(0xFF4F5E73), restBright = Color(0xFF8296B0),
        restGlow = Color(0xFF61738C),
        stressColor = Color(0xFFC2A274), stressDeep = Color(0xFF61738C), stressBright = Color(0xFFB5776A),
        stressGlow = Color(0xFFC2A274),
        scenicCenter = Color(0xFF283040), scenicEdge = Color(0xFF232A38), scenicStar = Color(0xFF8A909C),
        cardFillTop = Color(0xFF363D4E), cardFillBottom = Color(0xFF323949),
        gold = Color(0xFFC4CAE0), goldLight = Color(0xFFD3D8E6), goldDeep = Color(0xFFA3ABC2),
        goldDeepText = Color(0xFF232A38), signalYellow = Color(0xFFD9BC82),
        heroFill = Color(0x0DFFFFFF), heroBorder = Color(0x00000000),
    )

    val light: PaletteTokens = LightTokens.copy(
        surfaceBase = Color(0xFFEEF0F4), surfaceRaised = Color(0xFFFFFFFF), surfaceOverlay = Color(0xFFFFFFFF),
        surfaceInset = Color(0xFFE5E8EE), hairline = Color(0xFFDCE0E7), hairlineStrong = Color(0xFFC8CDD7),
        textPrimary = Color(0xFF1E2533), textSecondary = Color(0xFF4E5666), textTertiary = Color(0xFF767E8C),
        glowAmbient = Color(0xFFE3E7F0),
        accent = Color(0xFF4F5D80), accentHover = Color(0xFF6A789C), accentMuted = Color(0xFFE3E7F0),
        focusRing = Color(0xFF4F5D80),
        recovery000 = Color(0xFF9A5A50), recovery030 = Color(0xFFA5705C), recovery055 = Color(0xFFA88B5C),
        recovery078 = Color(0xFF789684), recovery100 = Color(0xFF5F8270),
        strain000 = Color(0xFF7E5449), strain033 = Color(0xFF8F6152), strain066 = Color(0xFFA06E5F),
        strain100 = Color(0xFFB07E6E),
        sleepAwake = Color(0xFF9AA2AE), sleepLight = Color(0xFF6F84A2), sleepDeep = Color(0xFF45566F),
        sleepREM = Color(0xFF8A9BC0),
        zone1 = Color(0xFF6F84A2), zone2 = Color(0xFF5F8590), zone3 = Color(0xFFA88B5C), zone4 = Color(0xFFB07E6E),
        zone5 = Color(0xFFA86A5E),
        statusPositive = Color(0xFF5F8270), statusWarning = Color(0xFFA88B5C), statusCritical = Color(0xFFA86A5E),
        metricCyan = Color(0xFF5F8590), metricPurple = Color(0xFF6F7CA8), metricAmber = Color(0xFFA88B5C),
        metricRose = Color(0xFFA86A5E),
        chargeColor = Color(0xFF6E8B7B), chargeDeep = Color(0xFF557063), chargeBright = Color(0xFF8CA697),
        chargeGlow = Color(0xFF6E8B7B),
        effortColor = Color(0xFFA06E5F), effortDeep = Color(0xFF7E5449), effortBright = Color(0xFFB98A79),
        effortGlow = Color(0xFFA06E5F),
        restColor = Color(0xFF5A6E8A), restDeep = Color(0xFF45566F), restBright = Color(0xFF6F84A2),
        restGlow = Color(0xFF5A6E8A),
        stressColor = Color(0xFFA88B5C), stressDeep = Color(0xFF5A6E8A), stressBright = Color(0xFFA86A5E),
        stressGlow = Color(0xFFA88B5C),
        scenicCenter = Color(0xFFFFFFFF), scenicEdge = Color(0xFFF0F2F5), scenicStar = Color(0xFFD5DAE0),
        cardFillTop = Color(0xFFFFFFFF), cardFillBottom = Color(0xFFFAFBFC),
        gold = Color(0xFF4F5D80), goldLight = Color(0xFF6A789C), goldDeep = Color(0xFF3C4866),
        goldDeepText = Color(0xFFFFFFFF), signalYellow = Color(0xFFB8913E),
        heroBorder = Color(0x14000000),
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

    /**
     * The unfilled track of the liquid bars (Key Metrics tiles, workout bars), or null to keep upstream's. Upstream
     * paints it an opaque near-black ported from the Swift renderer, whatever the theme; on slate it read as a hole
     * in the card, so this look recesses it a step below the card instead.
     */
    val tubeTrack: Color?
        get() = when {
            !enabled -> null
            Palette.isLight -> Color(0xFFE1E5EC)
            else -> Color(0xFF272E3C)
        }

    /**
     * The ring gauges' centre-number weight. Upstream hardcodes Bold there, so its "Gauge numbers: Softer" setting
     * reached only the liquid vessels. Softer now reaches the rings too: Regular under this look, after the
     * reference's light numerals, and SemiBold otherwise, the weight the vessels use for Softer.
     */
    fun ringNumeralWeight(): FontWeight = when {
        AppearancePrefs.gaugeNumerals != GaugeNumeralStyle.SOFT -> FontWeight.Bold
        enabled -> FontWeight.Normal
        else -> FontWeight.SemiBold
    }

    /** The Appearance row: a divider and an Off / On control, with a note when the typeface awaits a relaunch. */
    @Composable
    fun SettingsRow() {
        val context = LocalContext.current
        SettingsRowDivider()
        SettingsFormRow(label = stringResource(R.string.fork_look_name)) {
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
