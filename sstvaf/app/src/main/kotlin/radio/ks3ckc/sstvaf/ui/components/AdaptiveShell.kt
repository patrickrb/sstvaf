package radio.ks3ckc.sstvaf.ui.components

/**
 * Layout decisions for the app shell so it adapts to tablets and landscape
 * phones instead of shipping the phone-portrait layout at every size
 * (issue #20 — Redmi Redpad 2 and friends in landscape).
 *
 * The logic here is pure so it can be unit-tested; the Composable shell in
 * [radio.ks3ckc.sstvaf.SstvAfApp] reads `LocalConfiguration.screenWidthDp` and
 * feeds it in. Keeping the threshold in one tested place also stops the two
 * navigation surfaces (bottom [TabBar] vs. side [TabRail]) from drifting apart.
 */
object AdaptiveShell {
    /**
     * Width (in dp) at or above which the shell moves navigation to a side rail.
     *
     * 600dp is the standard Material compact→medium boundary. Below it we're on a
     * phone in portrait (or a very small window) where a bottom bar is the right
     * ergonomics; at or above it — a tablet in either orientation, or a phone
     * turned to landscape — a side rail reclaims the vertical space the bottom
     * bar + TX strip would otherwise eat, which is the whole point on a short,
     * wide landscape canvas.
     */
    const val RAIL_MIN_WIDTH_DP = 600

    /** True when navigation should be a side rail rather than the bottom bar. */
    fun useNavigationRail(screenWidthDp: Int): Boolean =
        screenWidthDp >= RAIL_MIN_WIDTH_DP
}
