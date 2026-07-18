package com.example.bib_vault.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val SWIPE_THRESHOLD_PX = 100f

/**
 * Smooth pinch-zoom / pan image surface with double-tap zoom and swipe navigation.
 */
@Composable
fun ZoomableImage(
    bitmap: Bitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    canSwipePrev: Boolean = false,
    canSwipeNext: Boolean = false,
    onSwipePrev: () -> Unit = {},
    onSwipeNext: () -> Unit = {},
    onTap: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val viewConfiguration = LocalViewConfiguration.current

    var scale by remember { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var layoutSize by remember { mutableStateOf(Size.Zero) }
    var animationJob by remember { mutableStateOf<Job?>(null) }

    val canSwipePrevState = rememberUpdatedState(canSwipePrev)
    val canSwipeNextState = rememberUpdatedState(canSwipeNext)
    val onSwipePrevState = rememberUpdatedState(onSwipePrev)
    val onSwipeNextState = rememberUpdatedState(onSwipeNext)
    val onTapState = rememberUpdatedState(onTap)

    fun clampOffset(raw: Offset, currentScale: Float, size: Size): Offset {
        if (size == Size.Zero || currentScale <= 1.01f) return Offset.Zero
        val maxX = (size.width * (currentScale - 1f)) / 2f
        val maxY = (size.height * (currentScale - 1f)) / 2f
        return Offset(
            x = raw.x.coerceIn(-maxX, maxX),
            y = raw.y.coerceIn(-maxY, maxY)
        )
    }

    fun zoomAround(
        centroid: Offset,
        currentScale: Float,
        currentOffset: Offset,
        zoom: Float
    ): Pair<Float, Offset> {
        val newScale = (currentScale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        if (newScale == currentScale) {
            return newScale to clampOffset(currentOffset, newScale, layoutSize)
        }
        val scaleRatio = newScale / currentScale
        val center = Offset(layoutSize.width / 2f, layoutSize.height / 2f)
        val focus = centroid - center
        val newOffset = (currentOffset - focus) * scaleRatio + focus
        return newScale to clampOffset(newOffset, newScale, layoutSize)
    }

    fun animateTo(targetScale: Float, targetOffset: Offset) {
        animationJob?.cancel()
        val startScale = scale
        val startOffset = offset
        val endOffset = clampOffset(targetOffset, targetScale, layoutSize)
        animationJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = spring(stiffness = 400f, dampingRatio = 0.85f)
            ) { t, _ ->
                scale = startScale + (targetScale - startScale) * t
                offset = Offset(
                    x = startOffset.x + (endOffset.x - startOffset.x) * t,
                    y = startOffset.y + (endOffset.y - startOffset.y) * t
                )
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { layoutSize = it.toSize() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTapState.value() },
                    onDoubleTap = { tapOffset ->
                        if (scale > 1.1f) {
                            animateTo(MIN_SCALE, Offset.Zero)
                        } else {
                            val center = Offset(layoutSize.width / 2f, layoutSize.height / 2f)
                            val focus = tapOffset - center
                            animateTo(DOUBLE_TAP_SCALE, focus * (1f - DOUBLE_TAP_SCALE))
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    animationJob?.cancel()

                    var totalPan = Offset.Zero
                    var pastSlop = false
                    var isSwipe = false
                    var transformed = false

                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount == 0) {
                            if (!transformed && isSwipe && abs(totalPan.x) > abs(totalPan.y)) {
                                when {
                                    totalPan.x <= -SWIPE_THRESHOLD_PX && canSwipeNextState.value ->
                                        onSwipeNextState.value()
                                    totalPan.x >= SWIPE_THRESHOLD_PX && canSwipePrevState.value ->
                                        onSwipePrevState.value()
                                }
                            } else if (transformed && scale < 1.02f) {
                                // Snap back to a clean fit when almost reset.
                                scale = MIN_SCALE
                                offset = Offset.Zero
                            } else if (transformed) {
                                offset = clampOffset(offset, scale, layoutSize)
                            }
                            break
                        }

                        if (pressedCount >= 2 || scale > 1.05f) {
                            transformed = true
                            isSwipe = false
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (zoom != 1f) {
                                val (newScale, newOffset) = zoomAround(
                                    centroid = centroid,
                                    currentScale = scale,
                                    currentOffset = offset,
                                    zoom = zoom
                                )
                                scale = newScale
                                offset = clampOffset(newOffset + pan, newScale, layoutSize)
                            } else {
                                offset = clampOffset(offset + pan, scale, layoutSize)
                            }
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                            continue
                        }

                        val pan = event.calculatePan()
                        totalPan += pan
                        if (!pastSlop && hypot(totalPan.x, totalPan.y) > touchSlop) {
                            pastSlop = true
                            isSwipe = abs(totalPan.x) > abs(totalPan.y)
                            if (!isSwipe) break
                        }
                        if (isSwipe) {
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                    } while (true)
                }
            }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    transformOrigin = TransformOrigin.Center
                }
        )
    }
}
