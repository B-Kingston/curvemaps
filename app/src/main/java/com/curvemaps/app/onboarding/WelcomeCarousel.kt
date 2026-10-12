package com.curvemaps.app.onboarding

import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.curvemaps.app.LocationPermission
import com.curvemaps.app.R
import com.curvemaps.app.media.MediaPermission
import com.curvemaps.app.ui.theme.CurveMapsDarkTheme
import com.curvemaps.app.ui.theme.CurveMapsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CurveMaps.Welcome"

/** Content description of the top-right Skip control (test and visual-runner seam). */
const val SKIP_WELCOME_DESCRIPTION = "Skip welcome"

/** Which M3 Expressive shape a page's hero is drawn in ([MaterialShapes]). */
internal enum class HeroShape { COOKIE_9_SIDED, CLOVER_4_LEAF, COOKIE_6_SIDED, SOFT_BURST }

/** Which colour-scheme container fills a hero (the glyph uses its `on` role). */
internal enum class HeroTone { PRIMARY_CONTAINER, SECONDARY_CONTAINER, TERTIARY_CONTAINER }

/**
 * One page's hero as designed: [sizeDp] is the shape's box, [glyphDp] the
 * Material Symbol inside it (0 where the page draws its own art), [gapDp] the
 * space between hero and title.
 */
internal data class HeroSpec(
    val shape: HeroShape,
    val tone: HeroTone,
    val sizeDp: Int,
    val glyphDp: Int,
    val gapDp: Int,
)

internal object WelcomeHero {
    fun spec(page: WelcomePage): HeroSpec = when (page) {
        WelcomePage.WELCOME -> HeroSpec(HeroShape.COOKIE_9_SIDED, HeroTone.PRIMARY_CONTAINER, 220, 0, 32)
        WelcomePage.SAFETY -> HeroSpec(HeroShape.CLOVER_4_LEAF, HeroTone.TERTIARY_CONTAINER, 220, 96, 32)
        WelcomePage.LOCATION -> HeroSpec(HeroShape.COOKIE_6_SIDED, HeroTone.SECONDARY_CONTAINER, 220, 56, 32)
        WelcomePage.MEDIA -> HeroSpec(HeroShape.SOFT_BURST, HeroTone.PRIMARY_CONTAINER, 220, 72, 32)
        WelcomePage.MAP -> HeroSpec(HeroShape.COOKIE_9_SIDED, HeroTone.TERTIARY_CONTAINER, 200, 88, 28)
    }

    /** Degrees the hero shape turns per page of swipe distance. */
    const val DEGREES_PER_PAGE = 45f

    /**
     * The hero shape turns a little as its page slides, so a swipe feels like it
     * carries the shape along. [pageOffset] is the page's distance from the
     * settled page in pages (negative = left). Reduced motion (animator scale 0)
     * keeps the shape still.
     */
    fun rotationDegrees(pageOffset: Float, reducedMotion: Boolean): Float =
        if (reducedMotion) 0f else pageOffset.coerceIn(-1f, 1f) * DEGREES_PER_PAGE
}

/** Pure sizing decisions for the welcome pages (pinned by `WelcomeLayoutTest`). */
internal object WelcomeLayout {
    /** Widest the page content, card and buttons grow on a wide window. */
    val CONTENT_MAX = 560.dp
    val COMPACT_CONTENT_MAX = 840.dp

    const val MIN_HERO_DP = 96
    private const val HERO_SHARE = 0.36f

    /** Short and wide (phone landscape): the hero sits beside the text and the controls share one row. */
    fun isCompact(width: Dp, height: Dp): Boolean = width >= 520.dp && height < 520.dp

    /**
     * The hero's size: its designed size where the page has room, shrinking
     * with the height the page is given (and with the font scale, which grows
     * the text under it) down to [MIN_HERO_DP], so title and body stay in view.
     */
    fun heroSize(designDp: Int, pageHeight: Dp, fontScale: Float, sideBySide: Boolean): Float {
        val cap = if (sideBySide) minOf(designDp, 168) else designDp
        val share = if (sideBySide) 0.9f else HERO_SHARE / fontScale.coerceAtLeast(1f)
        return (pageHeight.value * share).coerceIn(MIN_HERO_DP.toFloat(), cap.toFloat())
    }
}

/**
 * First-run welcome carousel. The safety page must be acknowledged before
 * Skip appears or swiping can pass it. Each permission is requested on the page that
 * explains it: location fires the system dialog once on arrival, notification
 * access opens the system screen from its button (it leaves the app, so it is
 * never automatic), and the map page explains .motomap files and can hand the
 * planner a "choose file" or "open Maps" action. Skipping or finishing calls
 * [onFinish]; the caller persists completion. Drawn in the dark scheme.
 */
@Composable
fun WelcomeCarousel(onFinish: (WelcomeExit) -> Unit) {
    CurveMapsDarkTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            WelcomeCarouselContent(onFinish)
        }
    }
}

@Composable
private fun WelcomeCarouselContent(onFinish: (WelcomeExit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { WelcomePage.entries.size })
    val page = WelcomePage.entries[pagerState.currentPage]

    var locationGranted by rememberSaveable { mutableStateOf(LocationPermission.isGranted(context)) }
    var locationAsked by rememberSaveable { mutableStateOf(false) }
    var safetyAcknowledged by rememberSaveable { mutableStateOf(false) }
    var mediaGranted by rememberSaveable { mutableStateOf(MediaPermission.isGranted(context)) }
    val mapStatus by produceState<WelcomeMapStatus?>(null) {
        value = withContext(Dispatchers.IO) { WelcomeStore.mapStatus(context) }
    }
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        locationGranted = LocationPermission.isGranted(context)
        Log.i(TAG, "Location permission " + if (locationGranted) "granted" else "denied")
    }
    val requestLocation = {
        locationAsked = true
        locationLauncher.launch(LocationPermission.requestedPermissions)
    }

    // Grants made in system Settings (notification access, or location after a
    // permanent denial) arrive while we are paused; re-read them on resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationGranted = LocationPermission.isGranted(context)
                mediaGranted = MediaPermission.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Arriving on the location page (button or swipe) asks once.
    val settled = WelcomePage.entries[pagerState.settledPage]
    LaunchedEffect(settled) {
        if (WelcomeModel.shouldAutoRequestLocation(settled, locationGranted, locationAsked)) {
            requestLocation()
        }
    }

    val goTo: (WelcomePage) -> Unit = { target ->
        scope.launch { pagerState.animateScrollToPage(target.ordinal) }
    }
    val advance: () -> Unit = { page.next()?.let(goTo) ?: onFinish(WelcomeExit.NONE) }
    BackHandler(enabled = page.previous() != null) { page.previous()?.let(goTo) }

    val map = mapStatus ?: WelcomeMapStatus(dataPresent = true, regionName = "")
    val primary = WelcomeModel.primaryAction(page, locationGranted, mediaGranted, map)
    val showSkip = WelcomeModel.showSkip(page, safetyAcknowledged)
    val onPrimary: () -> Unit = {
        when (primary) {
            WelcomePrimary.NEXT -> advance()
            WelcomePrimary.ACKNOWLEDGE_SAFETY -> {
                safetyAcknowledged = true
                advance()
            }
            WelcomePrimary.ALLOW_LOCATION -> requestLocation()
            WelcomePrimary.ENABLE_MEDIA ->
                runCatching { context.startActivity(MediaPermission.settingsIntent()) }
                    .onFailure { Log.w(TAG, "Notification settings unavailable", it) }
            WelcomePrimary.CHOOSE_MAP_FILE -> onFinish(WelcomeExit.CHOOSE_MAP_FILE)
            WelcomePrimary.FINISH -> onFinish(WelcomeExit.NONE)
        }
    }
    val primaryEnabled = page != WelcomePage.MAP || mapStatus != null
    // Secondary actions sit under the primary button in one reserved slot, so the
    // dots and the primary button stay where they are from page to page.
    val secondary = buildList<SecondaryAction> {
        if (page == WelcomePage.MAP && mapStatus?.dataPresent == false) {
            add(SecondaryAction("Download in Maps", R.drawable.ms_download) { onFinish(WelcomeExit.OPEN_MAPS) })
        }
        if (page == WelcomePage.MAP) {
            if (mapStatus?.ready == false) add(SecondaryAction("Later", null) { onFinish(WelcomeExit.NONE) })
        } else if (primary != WelcomePrimary.NEXT && primary != WelcomePrimary.ACKNOWLEDGE_SAFETY && !page.isLast) {
            add(SecondaryAction("Not now", null, advance))
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        val compact = WelcomeLayout.isCompact(maxWidth, maxHeight)
        Column(modifier = Modifier.fillMaxSize()) {
            if (!compact) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp),
                ) {
                    if (showSkip) {
                        SkipButton(Modifier.align(Alignment.CenterEnd)) { onFinish(WelcomeExit.NONE) }
                    }
                }
            }
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = WelcomeModel.swipeEnabled(page, safetyAcknowledged),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { index ->
                val shown = WelcomePage.entries[index]
                WelcomePageContent(
                    page = shown,
                    pageOffset = { (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction },
                    reducedMotion = reducedMotion,
                    locationGranted = locationGranted,
                    locationAsked = locationAsked,
                    mediaGranted = mediaGranted,
                    mapStatus = mapStatus,
                    compact = compact,
                )
            }
            val (glyph, trailing) = primaryGlyph(primary)
            // One button per page: a press ripple never carries over to the next page.
            val primaryButton = @Composable { modifier: Modifier ->
                key(page) { Button(
                    onClick = onPrimary,
                    enabled = primaryEnabled,
                    modifier = modifier.heightIn(min = 56.dp),
                ) {
                    ButtonContent(primary.label, glyph, trailing)
                } }
            }
            if (compact) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .widthIn(max = WelcomeLayout.COMPACT_CONTENT_MAX)
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    PageIndicator(current = page)
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showSkip) SkipButton(Modifier) { onFinish(WelcomeExit.NONE) }
                        secondary.forEach { SecondaryButton(it, Modifier) }
                        primaryButton(Modifier)
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .widthIn(max = WelcomeLayout.CONTENT_MAX)
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
                ) {
                    PageIndicator(current = page)
                    Spacer(Modifier.height(24.dp))
                    primaryButton(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.heightIn(min = 56.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        secondary.forEach { SecondaryButton(it, Modifier) }
                    }
                }
            }
        }
    }
}

private class SecondaryAction(val label: String, @DrawableRes val glyph: Int?, val onClick: () -> Unit)

@Composable
private fun SkipButton(modifier: Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = SKIP_WELCOME_DESCRIPTION },
    ) {
        Text("Skip", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SecondaryButton(action: SecondaryAction, modifier: Modifier) {
    if (action.glyph != null) {
        FilledTonalButton(onClick = action.onClick, modifier = modifier.heightIn(min = 48.dp)) {
            ButtonContent(action.label, action.glyph, trailing = false)
        }
    } else {
        TextButton(onClick = action.onClick, modifier = modifier.heightIn(min = 48.dp)) {
            Text(action.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The glyph on a primary button and whether it trails the label. */
@DrawableRes
private fun primaryGlyph(primary: WelcomePrimary): Pair<Int, Boolean> = when (primary) {
    WelcomePrimary.NEXT -> R.drawable.ms_arrow_forward to true
    WelcomePrimary.ACKNOWLEDGE_SAFETY -> R.drawable.ms_check to false
    WelcomePrimary.ALLOW_LOCATION -> R.drawable.ms_my_location to false
    WelcomePrimary.ENABLE_MEDIA -> R.drawable.ms_open_in_new to true
    WelcomePrimary.CHOOSE_MAP_FILE -> R.drawable.ms_file_open to false
    WelcomePrimary.FINISH -> R.drawable.ms_route to false
}

@Composable
private fun ButtonContent(label: String, @DrawableRes glyph: Int, trailing: Boolean) {
    val icon = @Composable {
        Icon(painterResource(glyph), contentDescription = null, modifier = Modifier.size(22.dp))
    }
    if (!trailing) {
        icon()
        Spacer(Modifier.width(8.dp))
    }
    Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    if (trailing) {
        Spacer(Modifier.width(8.dp))
        icon()
    }
}

@Composable
private fun WelcomePageContent(
    page: WelcomePage,
    pageOffset: () -> Float,
    reducedMotion: Boolean,
    locationGranted: Boolean,
    locationAsked: Boolean,
    mediaGranted: Boolean,
    mapStatus: WelcomeMapStatus?,
    compact: Boolean,
) {
    val spec = WelcomeHero.spec(page)
    val fontScale = LocalDensity.current.fontScale
    val surface = MaterialTheme.colorScheme.surface
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = compact
        val pageHeight = maxHeight
        val hero = WelcomeLayout.heroSize(spec.sizeDp, maxHeight, fontScale, sideBySide).dp
        val scroll = rememberScrollState()
        val text = @Composable {
            PageText(page, locationGranted, locationAsked, mediaGranted, mapStatus)
        }
        val card = @Composable { PageCard(page, mapStatus) }
        val heroShape = @Composable { WelcomeHeroShape(page, spec, hero, pageOffset, reducedMotion) }
        if (sideBySide) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                heroShape()
                Box(Modifier.weight(1f, fill = false).widthIn(max = 480.dp).fillMaxHeight()) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .heightIn(min = pageHeight - 16.dp),
                    ) {
                        text()
                        Spacer(Modifier.height(16.dp))
                        card()
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().verticalScroll(scroll),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .widthIn(max = WelcomeLayout.CONTENT_MAX)
                        .fillMaxWidth()
                        .heightIn(min = pageHeight)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    heroShape()
                    Spacer(Modifier.height(spec.gapDp.dp * (hero.value / spec.sizeDp)))
                    text()
                    Spacer(Modifier.height(if (hero.value < spec.sizeDp) 20.dp else 32.dp))
                    card()
                }
            }
        }
        // A soft fade where the page continues, so cut-off text reads as "scroll".
        FadeEdge(Modifier.align(Alignment.BottomCenter), surface, visible = scroll.canScrollForward, top = false)
        FadeEdge(Modifier.align(Alignment.TopCenter), surface, visible = scroll.canScrollBackward, top = true)
    }
}

@Composable
private fun FadeEdge(modifier: Modifier, color: Color, visible: Boolean, top: Boolean) {
    if (!visible) return
    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(
                Brush.verticalGradient(
                    if (top) listOf(color, Color.Transparent) else listOf(Color.Transparent, color),
                ),
            ),
    )
}

@Composable
private fun PageText(
    page: WelcomePage,
    locationGranted: Boolean,
    locationAsked: Boolean,
    mediaGranted: Boolean,
    mapStatus: WelcomeMapStatus?,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            page.title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        val body = when (page) {
            WelcomePage.WELCOME -> WelcomeModel.WELCOME_BODY
            WelcomePage.SAFETY -> WelcomeModel.SAFETY_BODY
            WelcomePage.LOCATION -> WelcomeModel.LOCATION_BODY
            WelcomePage.MEDIA -> WelcomeModel.MEDIA_LEAD
            WelcomePage.MAP -> mapStatus?.let(WelcomeModel::mapBody) ?: ""
        }
        Text(
            body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        when (page) {
            WelcomePage.LOCATION -> StatusChip(
                text = WelcomeModel.locationStatus(locationGranted, locationAsked),
                ok = locationGranted,
                offGlyph = R.drawable.ms_location_off,
            )
            WelcomePage.MEDIA -> StatusChip(
                text = WelcomeModel.mediaStatus(mediaGranted),
                ok = mediaGranted,
                offGlyph = R.drawable.ms_notifications_off,
            )
            else -> Unit
        }
    }
}

@Composable
private fun PageCard(page: WelcomePage, mapStatus: WelcomeMapStatus?) {
    when {
        page == WelcomePage.WELCOME -> InfoCard(
            rows = WelcomeModel.WELCOME_FEATURES.zip(
                listOf(R.drawable.ms_route, R.drawable.ms_record_voice_over, R.drawable.ms_offline_pin),
            ),
            tiles = true,
        )
        page == WelcomePage.SAFETY -> NoteCard(WelcomeModel.SAFETY_DATA_NOTE, R.drawable.ms_warning_fill)
        page == WelcomePage.MEDIA -> InfoCard(
            rows = listOf(
                WelcomeModel.MEDIA_PRIVACY_NOTE to R.drawable.ms_visibility_off,
                WelcomeModel.MEDIA_OPTIONAL_NOTE to R.drawable.ms_volume_up,
            ),
            tiles = false,
        )
        page == WelcomePage.MAP && mapStatus?.ready == false -> MapPartsCard(WelcomeModel.MAP_HOW_TO)
    }
}

/** The page's hero: an M3 Expressive shape with its glyph or art inside. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WelcomeHeroShape(
    page: WelcomePage,
    spec: HeroSpec,
    heroSize: Dp,
    pageOffset: () -> Float,
    reducedMotion: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val (fill, onFill) = when (spec.tone) {
        HeroTone.PRIMARY_CONTAINER -> colors.primaryContainer to colors.onPrimaryContainer
        HeroTone.SECONDARY_CONTAINER -> colors.secondaryContainer to colors.onSecondaryContainer
        HeroTone.TERTIARY_CONTAINER -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    val polygon = when (spec.shape) {
        HeroShape.COOKIE_9_SIDED -> MaterialShapes.Cookie9Sided
        HeroShape.CLOVER_4_LEAF -> MaterialShapes.Clover4Leaf
        HeroShape.COOKIE_6_SIDED -> MaterialShapes.Cookie6Sided
        HeroShape.SOFT_BURST -> MaterialShapes.SoftBurst
    }
    val shape: Shape = polygon.toShape()
    val scale = heroSize.value / spec.sizeDp
    Box(Modifier.size(heroSize), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = WelcomeHero.rotationDegrees(pageOffset(), reducedMotion) }
                .background(fill, shape),
        )
        when (page) {
            WelcomePage.WELCOME -> TwistyRoad(onFill, fill, Modifier.fillMaxSize())
            WelcomePage.SAFETY -> HeroGlyph(R.drawable.ms_two_wheeler_fill, spec.glyphDp * scale, onFill)
            WelcomePage.LOCATION -> {
                val halo = colors.primary
                Box(
                    Modifier
                        .size((112 * scale).dp)
                        .drawBehind {
                            drawCircle(halo.copy(alpha = 0.07f), radius = size.minDimension / 2 + (34 * scale).dp.toPx())
                            drawCircle(halo.copy(alpha = 0.14f), radius = size.minDimension / 2 + (16 * scale).dp.toPx())
                        }
                        .background(colors.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    HeroGlyph(R.drawable.ms_near_me_fill, spec.glyphDp * scale, colors.onPrimaryContainer)
                }
            }
            WelcomePage.MEDIA -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((14 * scale).dp, Alignment.CenterVertically),
            ) {
                HeroGlyph(R.drawable.ms_headphones_fill, spec.glyphDp * scale, onFill)
                MiniPlayer(onFill, fill, scale)
            }
            WelcomePage.MAP -> HeroGlyph(R.drawable.ms_map_fill, spec.glyphDp * scale, onFill)
        }
    }
}

@Composable
private fun HeroGlyph(@DrawableRes glyph: Int, sizeDp: Float, tint: Color) {
    Icon(painterResource(glyph), contentDescription = null, tint = tint, modifier = Modifier.size(sizeDp.dp))
}

/** The design's decorative player: previous, pause, next. Not interactive. */
@Composable
private fun MiniPlayer(onFill: Color, fill: Color, scale: Float) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clearAndSetSemantics { }
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f), CircleShape)
            .padding(4.dp),
    ) {
        PlayerKey(R.drawable.ms_skip_previous_fill, onFill, width = 32, background = null, scale = scale)
        PlayerKey(R.drawable.ms_pause_fill, fill, width = 44, background = onFill, scale = scale)
        PlayerKey(R.drawable.ms_skip_next_fill, onFill, width = 32, background = null, scale = scale)
    }
}

@Composable
private fun PlayerKey(@DrawableRes glyph: Int, tint: Color, width: Int, background: Color?, scale: Float) {
    Box(
        Modifier
            .size(width = (width * scale).dp, height = (32 * scale).dp)
            .then(if (background != null) Modifier.background(background, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(glyph), contentDescription = null, tint = tint, modifier = Modifier.size((20 * scale).dp))
    }
}

/** The welcome page's twisty-road art, drawn in the hero's 220-unit space. */
@Composable
private fun TwistyRoad(road: Color, dash: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val k = size.width / 220f
        val path = Path().apply {
            moveTo(74f * k, 168f * k)
            cubicTo(74f * k, 140f * k, 146f * k, 146f * k, 146f * k, 118f * k)
            cubicTo(146f * k, 90f * k, 80f * k, 96f * k, 82f * k, 70f * k)
            cubicTo(84f * k, 44f * k, 132f * k, 52f * k, 136f * k, 50f * k)
        }
        drawPath(path, road, style = Stroke(22f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(
            path,
            dash,
            style = Stroke(
                3f * k,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * k, 9f * k)),
            ),
        )
    }
}

/**
 * A non-interactive outlined chip (32 dp, small shape) reporting a permission:
 * the design's "Not allowed yet" treatment. Granted swaps to a success check.
 */
@Composable
private fun StatusChip(text: String, ok: Boolean, @DrawableRes offGlyph: Int) {
    val tint = if (ok) CurveMapsTheme.colors.success else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(top = 8.dp)
            .heightIn(min = 32.dp)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.small)
            .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Icon(
            painterResource(if (ok) R.drawable.ms_check_circle_fill else offGlyph),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    }
}

/** A `surfaceContainerLow` card of icon rows separated by inset dividers. */
@Composable
private fun InfoCard(rows: List<Pair<String, Int>>, tiles: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        rows.forEachIndexed { i, (label, glyph) ->
            if (i > 0) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            ListItem(
                modifier = Modifier.heightIn(min = 56.dp),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    if (tiles) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(glyph),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    } else {
                        Icon(
                            painterResource(glyph),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                headlineContent = {
                    Text(
                        label,
                        style = if (tiles) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        color = if (tiles) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

/** The safety page's outlined data-accuracy note with a tertiary warning icon. */
@Composable
private fun NoteCard(text: String, @DrawableRes glyph: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.large)
            .padding(16.dp),
    ) {
        Icon(painterResource(glyph), contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The map page's card: what a .motomap carries (chips) and where to get one. */
@Composable
private fun MapPartsCard(howTo: String) {
    val glyphs = listOf(R.drawable.ms_map, R.drawable.ms_route, R.drawable.ms_search)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(24.dp))
            .padding(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WelcomeModel.MAP_PARTS.zip(glyphs).forEach { (label, glyph) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                        .padding(start = 8.dp, end = 12.dp),
                ) {
                    Icon(
                        painterResource(glyph),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Text(howTo, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The page indicator: the current page is a 24 x 8 dp `primary` pill, the others
 * 8 dp `outlineVariant` dots. Announced as "Page n of N".
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PageIndicator(current: WelcomePage) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics {
            stateDescription = "Page ${current.ordinal + 1} of ${WelcomePage.entries.size}"
        },
    ) {
        WelcomePage.entries.forEach { page ->
            val active = page == current
            val width by animateDpAsState(
                if (active) 24.dp else 8.dp,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "dotWidth",
            )
            Box(
                Modifier
                    .size(width = width, height = 8.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
        }
    }
}
