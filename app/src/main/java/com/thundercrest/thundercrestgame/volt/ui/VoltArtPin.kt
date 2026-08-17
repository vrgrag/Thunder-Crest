package com.thundercrest.thundercrestgame.volt.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Where the painted card sits inside a background artwork, and where it
 * ends up on screen once [androidx.compose.ui.layout.ContentScale.Crop]
 * has had its way with the bitmap.
 *
 * The offline / invite screens are "artwork + buttons": the artwork
 * paints a card, the buttons must land under *that card*. Positioning
 * them at a fixed percentage of screen height only works on a device
 * whose aspect ratio happens to match the source art. On anything else
 * Crop scales to fill the short axis and shaves the long one, the card
 * slides, and the buttons drift away from it — read as skew, which is
 * exactly what pitfalls §12 warns about.
 *
 * So the placement is derived instead of guessed: replay the same
 * scale-and-centre maths Crop uses, and hand back the card edge in
 * layout coordinates.
 *
 * Bounds are fractions of the source bitmap, measured off the artwork
 * itself. Re-measure whenever the art is replaced — see
 * the gray-flow art guide.
 */
internal data class VoltArtCard(
    val artWidth: Int,
    val artHeight: Int,
    val bottom: Float,
) {
    /**
     * @param frameWidth  container width, as reported by BoxWithConstraints
     * @param frameHeight container height
     * @return the card's bottom edge and the artwork's bottom edge, both
     *   clamped into the container. The gap between them is the band the
     *   buttons may occupy.
     */
    fun projectInto(frameWidth: Dp, frameHeight: Dp): VoltArtBand {
        val w = frameWidth.value
        val h = frameHeight.value
        // Crop fills the container, so the scale is driven by whichever
        // axis needs the most magnification; the surplus on the other
        // axis is split evenly around the centre.
        val factor = max(w / artWidth, h / artHeight)
        val painted = artHeight * factor
        val top = (h - painted) / 2f
        return VoltArtBand(
            cardBottom = (top + bottom * painted).coerceIn(0f, h).dp,
            artBottom = min(h, top + painted).dp,
            frameHeight = frameHeight,
        )
    }
}

internal data class VoltArtBand(
    val cardBottom: Dp,
    val artBottom: Dp,
    val frameHeight: Dp,
) {
    /**
     * Top offset that pins a [blockHeight]-tall stack of buttons just
     * under the painted card.
     *
     * Thunder Crest artwork fills the lower half with character art, so
     * centering the stack in the whole under-card band drops the pills
     * onto Zeus's chest and reads as "buttons floating in the wrong
     * place". Pinning under the card keeps Accept / Skip / Retry glued
     * to the red plaque on every aspect ratio. The result is still
     * clamped so a heavily cropped bitmap can never push the buttons
     * off-screen.
     */
    /**
     * Top offset for the invite Accept/Skip stack — lowered into the
     * cloud band under the red plaque so the pills sit clearly below the
     * card rather than glued to its bottom border.
     */
    fun offsetForInvite(blockHeight: Dp, gap: Dp = 24.dp, safety: Dp = 12.dp): Dp {
        val bandTop = cardBottom.value + gap.value
        val bandBottom = min(artBottom.value, frameHeight.value) - safety.value
        val slack = bandBottom - bandTop - blockHeight.value
        val offset = bandTop + max(0f, slack) * 0.30f
        val ceiling = frameHeight.value - blockHeight.value - safety.value
        return max(0f, min(offset, ceiling)).dp
    }

    /**
     * Top offset for offline Retry — sits lower than Accept/Skip on the
     * invite art, but stays inside the cloud band under the red plaque.
     */
    fun offsetForOffline(blockHeight: Dp, gap: Dp = 28.dp, safety: Dp = 12.dp): Dp {
        val bandTop = cardBottom.value + gap.value
        val bandBottom = min(artBottom.value, frameHeight.value) - safety.value
        val slack = bandBottom - bandTop - blockHeight.value
        val offset = bandTop + max(0f, slack) * 0.38f
        val ceiling = frameHeight.value - blockHeight.value - safety.value
        return max(0f, min(offset, ceiling)).dp
    }

    fun offsetFor(blockHeight: Dp, gap: Dp = 16.dp, safety: Dp = 12.dp): Dp {
        val pinned = cardBottom.value + gap.value
        val ceiling = frameHeight.value - blockHeight.value - safety.value
        return max(0f, min(pinned, ceiling)).dp
    }
}

/**
 * Card bounds measured off the shipped artwork. Only the bottom edge
 * matters for layout — the cards are horizontally centred in all four
 * bitmaps, and Crop keeps a centred bitmap centred, so the buttons can
 * be centred against the container instead of the card.
 */
internal object VoltArtMetrics {
    val InvitePortrait = VoltArtCard(1080, 2400, 0.670f)
    val InviteLandscape = VoltArtCard(2400, 1080, 0.629f)
    val OfflinePortrait = VoltArtCard(1080, 2400, 0.652f)
    val OfflineLandscape = VoltArtCard(2400, 1080, 0.638f)
}
