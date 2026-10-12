package com.curvemaps.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.curvemaps.app.media.GloveTarget
import com.curvemaps.app.ui.theme.CurveMapsDarkTheme
import com.curvemaps.app.routing.navigation.VoiceDistanceUnit
import com.curvemaps.app.routing.suggestion.DiversionMeasure
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure text for the suggestion banner, shared with tests. */
object RideSuggestionFormat {
    /** "+6 min · +4 km · 9 km curvier" (signs follow the real numbers). */
    fun summary(measure: DiversionMeasure, unit: VoiceDistanceUnit): String {
        val minutes = (measure.extraTimeS / 60.0).roundToInt()
        val time = when {
            minutes == 0 -> "±0 min"
            minutes > 0 -> "+$minutes min"
            else -> "−${abs(minutes)} min"
        }
        val distance = signed(measure.extraDistanceM, unit)
        val curves = "${length(measure.curvyGainM, unit)} curvier"
        return "$time · $distance · $curves"
    }

    /**
     * The countdown ring's fill: how much of the run-up to the fork is left,
     * 1 when the offer was made and 0 at the fork (clamped; 0 when the fork is
     * not ahead of where the offer was made).
     */
    fun remainingFraction(forkOffsetM: Double, offsetAtProposalM: Double, coveredM: Double): Float {
        val span = forkOffsetM - offsetAtProposalM
        if (!span.isFinite() || span <= 0.0 || !coveredM.isFinite()) return 0f
        return ((forkOffsetM - coveredM) / span).coerceIn(0.0, 1.0).toFloat()
    }

    private fun signed(metres: Double, unit: VoiceDistanceUnit): String {
        val text = length(abs(metres), unit)
        return when {
            abs(metres) < 50.0 -> "±0 ${if (unit == VoiceDistanceUnit.IMPERIAL) "mi" else "km"}"
            metres > 0 -> "+$text"
            else -> "−$text"
        }
    }

    private fun length(metres: Double, unit: VoiceDistanceUnit): String {
        val value = if (unit == VoiceDistanceUnit.IMPERIAL) metres / 1_609.344 else metres / 1_000.0
        val suffix = if (unit == VoiceDistanceUnit.IMPERIAL) "mi" else "km"
        val rounded = (value * 10.0).roundToInt() / 10.0
        val text = if (rounded >= 10.0 || rounded == rounded.toInt().toDouble()) {
            rounded.roundToInt().toString()
        } else {
            "%.1f".format(Locale.US, rounded)
        }
        return "$text $suffix"
    }
}

/**
 * The pending scenic diversion on the ride HUD: what it costs and buys, with
 * glove-sized Skip (outlined) and Take (tertiary) buttons under a title and the
 * measured summary, on a `surfaceContainerHigh` card with a `tertiaryContainer`
 * route tile. Riding the named turn accepts it too, so the buttons are optional.
 *
 * [remainingFraction] draws the countdown ring (1 just offered, 0 at the fork);
 * null draws none. [monochrome] (B&W ride mode) swaps the orange tile and the
 * tertiary Take for neutral roles.
 */
@Composable
internal fun RideSuggestionBanner(
    measure: DiversionMeasure,
    unit: VoiceDistanceUnit,
    onTake: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    monochrome: Boolean = false,
    remainingFraction: Float? = null,
) {
    val summary = RideSuggestionFormat.summary(measure, unit)
    CurveMapsDarkTheme {
        val colors = MaterialTheme.colorScheme
        Surface(
            color = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
            shadowElevation = 6.dp,
            modifier = modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .semantics { contentDescription = "Scenic diversion: $summary" },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(if (monochrome) colors.surfaceContainerHighest else colors.tertiaryContainer),
                    ) {
                        Icon(
                            painterResource(R.drawable.ms_alt_route),
                            contentDescription = null,
                            tint = if (monochrome) colors.onSurface else colors.onTertiaryContainer,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Twistier road ahead",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    remainingFraction?.let { fraction ->
                        CircularProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            color = if (monochrome) colors.inverseSurface else colors.tertiary,
                            trackColor = colors.outlineVariant,
                            strokeWidth = 4.dp,
                            modifier = Modifier
                                .size(32.dp)
                                .semantics { contentDescription = "Offer closes soon" },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minWidth = GloveTarget * 1.5f, minHeight = GloveTarget)
                            .semantics { contentDescription = "Skip scenic diversion" },
                    ) {
                        Text("Skip", style = MaterialTheme.typography.titleMedium)
                    }
                    Button(
                        onClick = onTake,
                        colors = if (monochrome) {
                            ButtonDefaults.buttonColors(
                                containerColor = colors.inverseSurface,
                                contentColor = colors.inverseOnSurface,
                            )
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = colors.tertiary,
                                contentColor = colors.onTertiary,
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minWidth = GloveTarget * 1.5f, minHeight = GloveTarget)
                            .semantics { contentDescription = "Take scenic diversion" },
                    ) {
                        Icon(painterResource(R.drawable.ms_check), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Take", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

