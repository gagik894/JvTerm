/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalModifiers
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.protocol.MouseTrackingMode
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellIntegrationCommandRecord
import io.github.ketraterm.ui.swing.input.SwingTerminalInputController
import io.github.ketraterm.ui.swing.input.SwingTerminalInputHost
import io.github.ketraterm.ui.swing.input.SwingTerminalMouseController
import io.github.ketraterm.ui.swing.input.SwingTerminalMouseHost
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.search.TerminalSearchController
import io.github.ketraterm.ui.swing.search.TerminalSearchHost
import io.github.ketraterm.ui.swing.search.TerminalSearchState
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingSettingsProvider
import io.github.ketraterm.ui.swing.settings.SwingTerminalChrome
import io.github.ketraterm.ui.swing.suggestion.*
import io.github.ketraterm.ui.swing.viewport.SmoothRowScrollHost
import io.github.ketraterm.ui.swing.viewport.SmoothRowScroller
import io.github.ketraterm.ui.swing.viewport.SwingViewportController
import io.github.ketraterm.ui.swing.viewport.TerminalScrollbarOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.*
import java.awt.event.*
import java.lang.Runnable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Reusable Swing terminal component.
 *
 * The component consumes published render-cache snapshots from a
 * [TerminalSession] and paints terminal rows without knowing which transport
 * produced the bytes. Hosts own session creation, process lifecycle, and
 * connector choice outside this component.
 *
 * @param settingsProvider provider for immutable settings snapshots.
 * @param hostServices host-provided non-render services.
 */
class SwingTerminal
    @JvmOverloads
    constructor(
        private val settingsProvider: SwingSettingsProvider =
            SwingSettingsProvider { SwingSettings() },
        private val hostServices: SwingHostServices = SwingHostServices(),
    ) : JComponent(),
        SwingScrollbarScroller {
        private var session: TerminalSession? = null
        private var disposed: Boolean = false
        private var settings: SwingSettings = settingsProvider.currentSettings()
        private var metrics: SwingMetrics = buildMetrics(settings)
        private var terminalFocused: Boolean = false
        internal var cursorBlinkVisible: Boolean = true
        internal val cursorPresentationEnabled: Boolean
            get() = terminalFocused
        private var lastResizedColumns: Int = NO_RESIZE_DIMENSION
        private var lastResizedRows: Int = NO_RESIZE_DIMENSION
        private val unbindRunnable = Runnable { unbindOnEdt() }
        private val componentJob = SupervisorJob()
        private val uiCoroutineDispatcher =
            object : CoroutineDispatcher() {
                override fun isDispatchNeeded(context: CoroutineContext): Boolean = !SwingUtilities.isEventDispatchThread()

                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable,
                ) {
                    hostServices.uiDispatcher.dispatch(block)
                }
            }
        private val componentScope =
            CoroutineScope(componentJob + uiCoroutineDispatcher + CoroutineName("swing-terminal"))
        private var bindingJob: Job? = null
        private var suggestionJob: Job? = null
        private var activeSuggestionIsAutomatic: Boolean = false
        private val suggestionInvalidationListeners = CopyOnWriteArraySet<SwingShellSuggestionInvalidationListener>()
        private val suggestionEligibilityListeners = CopyOnWriteArraySet<SwingShellSuggestionEligibilityListener>()
        private val automaticSuggestionEligible = AtomicBoolean(settings.smartSuggestionsEnabled && settings.shellSuggestionsEnabled)

        internal val hasActiveRenderBinding: Boolean
            get() = bindingJob?.isActive == true

        internal val isCoroutineScopeActive: Boolean
            get() = componentJob.isActive

        private val visualGeometry = TerminalVisualViewportGeometry()
        private val painter = GridPainter(hostServices.fontResolver, visualGeometry.bidiLayout)
        private val visualBellController =
            TerminalVisualBellController {
                repaint()
            }
        private val viewportController = SwingViewportController(hostServices.viewportListener)
        private val renderCache =
            TerminalRenderCache(
                columns = settings.columns,
                rows = settings.rows,
                rowCapacityReserve = TRANSIENT_RENDER_ROW_RESERVE,
            )
        private val searchCache = TerminalRenderCache(settings.columns, settings.rows)
        private val shellIntegrationDecorations = TerminalShellIntegrationViewportDecorations()
        private val scrollbarOverlay = TerminalScrollbarOverlay()
        private val rowScroller =
            SmoothRowScroller(
                object : SmoothRowScrollHost {
                    override fun rowScrollOffset(): Double = viewportController.preciseOffset

                    override fun rowScrollHistorySize(): Int = renderCache.historySize

                    override fun applyRowScrollOffset(
                        offsetRows: Double,
                        scrollComplete: Boolean,
                    ): Boolean = this@SwingTerminal.applyRowScrollOffsetOnEdt(offsetRows, scrollComplete)
                },
            )
        private var hoveredPromptMarkerRow: Int = NO_PROMPT_MARKER_ROW
        private val terminalMouseListener =
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (handleScrollbarOverlayPressed(event)) return
                    mouseController.mouseListener.mousePressed(event)
                }

                override fun mouseReleased(event: MouseEvent) {
                    if (handleScrollbarOverlayReleased(event)) return
                    if (isInScrollbarOverlayGutter(event)) {
                        event.consume()
                        return
                    }
                    mouseController.mouseListener.mouseReleased(event)
                }

                override fun mouseExited(event: MouseEvent) {
                    if (hostServices.scrollbarOverlayEnabled && scrollbarOverlay.handleExited()) repaint()
                    mouseController.mouseListener.mouseExited(event)
                }
            }
        private val terminalMouseMotionListener =
            object : MouseMotionAdapter() {
                override fun mouseDragged(event: MouseEvent) {
                    if (handleScrollbarOverlayDragged(event)) return
                    if (isInScrollbarOverlayGutter(event)) {
                        event.consume()
                        return
                    }
                    mouseController.mouseMotionListener.mouseDragged(event)
                }

                override fun mouseMoved(event: MouseEvent) {
                    val changed =
                        hostServices.scrollbarOverlayEnabled &&
                            scrollbarOverlay.handleMoved(
                                x = event.x,
                                y = event.y,
                                settings = settings,
                                activeBuffer = renderCache.activeBuffer,
                                componentWidth = width,
                                componentHeight = height,
                            )
                    if (changed) repaint()
                    if (hostServices.scrollbarOverlayEnabled && scrollbarOverlay.hovered) {
                        hyperlinkController.clearHyperlinkHover()
                        updateHoveredPromptMarker(NO_PROMPT_MARKER_ROW)
                        return
                    }
                    mouseController.mouseMotionListener.mouseMoved(event)
                }
            }

        private val selectionController =
            TerminalSelectionController(
                object : TerminalSelectionHost {
                    override val settings: SwingSettings get() = this@SwingTerminal.settings
                    override val metrics: SwingMetrics get() = this@SwingTerminal.metrics
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override val contentYOffset: Double get() = this@SwingTerminal.visualGeometry.contentOriginY
                    override val componentWidth: Int get() = this@SwingTerminal.width
                    override val componentHeight: Int get() = this@SwingTerminal.height

                    override fun cellAt(
                        x: Int,
                        y: Int,
                    ): Long = this@SwingTerminal.cellAt(x, y, this@SwingTerminal.renderCache)

                    override fun scrollViewportByRows(deltaRows: Int): Boolean = rowScroller.scrollByRows(deltaRows)

                    override fun repaint() = this@SwingTerminal.repaint()

                    override fun requestFocusInWindow(): Boolean = this@SwingTerminal.requestFocusInWindow()
                },
            )

        private val commandInteractionController =
            TerminalCommandInteractionController(
                object : TerminalCommandInteractionHost {
                    override val session: TerminalSession? get() = this@SwingTerminal.session
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override val searchCache: TerminalRenderCache get() = this@SwingTerminal.searchCache

                    override fun cellAt(
                        x: Int,
                        y: Int,
                        cache: TerminalRenderCache,
                    ): Long = this@SwingTerminal.cellAt(x, y, cache)

                    override fun visibleGridRows(): Int = this@SwingTerminal.visibleGridRows()

                    override fun commandNavigationAnchorRow(): Int = this@SwingTerminal.commandNavigationAnchorRow()

                    override fun refreshRenderCacheFromSession(session: TerminalSession) =
                        this@SwingTerminal.refreshRenderCacheFromSession(session)

                    override fun refreshShellIntegrationDecorations(session: TerminalSession): Boolean =
                        this@SwingTerminal.refreshShellIntegrationDecorations(session)

                    override fun selectAbsoluteRows(
                        startAbsoluteRow: Long,
                        endAbsoluteRow: Long,
                        columns: Int,
                    ) = this@SwingTerminal.selectionController.selectAbsoluteRows(startAbsoluteRow, endAbsoluteRow, columns)

                    override fun scrollViewportTo(
                        offsetRows: Int,
                        historySize: Int,
                        boundSession: TerminalSession,
                    ): Boolean = this@SwingTerminal.scrollViewportToOnEdt(offsetRows, historySize, boundSession)

                    override fun repaint() = this@SwingTerminal.repaint()
                },
            )

        private val hyperlinkDiscoveryController =
            TerminalHyperlinkDiscoveryController(
                host =
                    object : TerminalHyperlinkDiscoveryHost {
                        override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                        override val hyperlinkDetector: SwingHyperlinkDetector
                            get() = this@SwingTerminal.hostServices.hyperlinkDetector

                        override fun repaintHyperlinkSpan(
                            startRow: Int,
                            startColumn: Int,
                            endRow: Int,
                            endColumn: Int,
                        ) = this@SwingTerminal.repaintHyperlinkSpan(startRow, startColumn, endRow, endColumn)
                    },
                scope = componentScope,
            )
        private val hyperlinkController =
            TerminalHyperlinkController(
                object : TerminalHyperlinkHost {
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override var cursor: Cursor
                        get() = this@SwingTerminal.cursor
                        set(value) {
                            this@SwingTerminal.cursor = value
                        }

                    override fun cellAt(
                        x: Int,
                        y: Int,
                    ): Long = this@SwingTerminal.cellAt(x, y, this@SwingTerminal.renderCache)

                    override fun repaintHyperlinkSpan(
                        startRow: Int,
                        startColumn: Int,
                        endRow: Int,
                        endColumn: Int,
                    ) = this@SwingTerminal.repaintHyperlinkSpan(startRow, startColumn, endRow, endColumn)

                    override fun hyperlinkIdAt(
                        row: Int,
                        column: Int,
                    ): Int = hyperlinkDiscoveryController.hyperlinkIdAt(row, column, this@SwingTerminal.renderCache)

                    override fun isHyperlinkResolvable(hyperlinkId: Int): Boolean = this@SwingTerminal.isHyperlinkResolvable(hyperlinkId)

                    override fun openHyperlink(hyperlinkId: Int): Boolean = this@SwingTerminal.openHyperlink(hyperlinkId)
                },
            )
        private val searchController =
            TerminalSearchController(
                object : TerminalSearchHost {
                    override val session: TerminalSession? get() = this@SwingTerminal.session
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override val searchCache: TerminalRenderCache get() = this@SwingTerminal.searchCache

                    override fun visibleGridRows(): Int = this@SwingTerminal.visibleGridRows()

                    override fun scrollViewportTo(
                        offsetRows: Int,
                        historySize: Int,
                        boundSession: TerminalSession,
                    ): Boolean = this@SwingTerminal.scrollViewportToOnEdt(offsetRows, historySize, boundSession)

                    override fun repaint() = this@SwingTerminal.repaint()
                },
            )
        private var shellSuggestionController: SwingShellSuggestionController? = null
        private val inputController =
            SwingTerminalInputController(
                object : SwingTerminalInputHost {
                    override val session: TerminalSession? get() = this@SwingTerminal.session

                    override fun updateHyperlinkActivationHover(active: Boolean) {
                        hyperlinkController.updateHyperlinkActivationHover(active)
                    }

                    override fun resetCursorBlink() {
                        if (resetCursorBlinkOnEdt()) renderFrameController.repaintBlinkState()
                    }

                    override fun setTerminalFocused(focused: Boolean) {
                        this@SwingTerminal.terminalFocused = focused
                    }

                    override fun repaintCursorState() {
                        renderFrameController.repaintCursorState()
                    }

                    override fun handleHostKeyPressed(event: KeyEvent): Boolean = hostServices.hostKeyHandler.handleKeyPressed(event)

                    override fun handleShellSuggestionKeyPressed(event: KeyEvent): Boolean =
                        shellSuggestionController?.handleKeyPressed(event) == true

                    override fun invalidateShellSuggestions() {
                        invalidateShellSuggestionsOnEdt()
                    }

                    override fun hideShellSuggestions() {
                        this@SwingTerminal.hideShellSuggestions()
                    }
                },
            )
        private val mouseController =
            SwingTerminalMouseController(
                object : SwingTerminalMouseHost {
                    override val settings: SwingSettings get() = this@SwingTerminal.settings
                    override val metrics: SwingMetrics get() = this@SwingTerminal.metrics
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override val session: TerminalInputEncoder? get() = this@SwingTerminal.session

                    override fun mouseTrackingMode(): MouseTrackingMode =
                        this@SwingTerminal
                            .session
                            ?.terminal
                            ?.getModeSnapshot()
                            ?.mouseTrackingMode ?: MouseTrackingMode.OFF

                    override fun encodeMouse(event: TerminalMouseEvent) {
                        this@SwingTerminal.session?.encodeMouse(event)
                    }

                    override fun cellAt(
                        x: Int,
                        y: Int,
                        cache: TerminalRenderCache,
                    ): Long = this@SwingTerminal.cellAt(x, y, cache)

                    override fun terminalPixelYAt(
                        y: Int,
                        cache: TerminalRenderCache,
                    ): Int = this@SwingTerminal.terminalPixelYAt(y, cache)

                    override fun visibleGridRows(): Int = this@SwingTerminal.visibleGridRows()

                    override fun scrollViewportByPreciseRows(deltaRows: Double): Boolean = rowScroller.scrollByPreciseRows(deltaRows)

                    override fun finishViewportScroll() {
                        rowScroller.finish()
                    }

                    override fun requestFocusInWindow(): Boolean = this@SwingTerminal.requestFocusInWindow()

                    override fun handlePromptMarkerMousePressed(event: MouseEvent): Boolean =
                        this@SwingTerminal.handlePromptMarkerMousePressed(event)

                    override fun handlePromptMarkerMouseMoved(event: MouseEvent): Boolean =
                        this@SwingTerminal.handlePromptMarkerMouseMoved(event)

                    override fun handlePromptMarkerMouseExited() {
                        this@SwingTerminal.updateHoveredPromptMarker(NO_PROMPT_MARKER_ROW)
                    }

                    override fun handleContextMenuMouseEvent(
                        event: MouseEvent,
                        forcedByShift: Boolean,
                    ): Boolean =
                        this@SwingTerminal.handleContextMenuMouseEvent(
                            event = event,
                            forcedByShift = forcedByShift,
                        )

                    override fun handleHyperlinkMousePressed(event: MouseEvent): Boolean = hyperlinkController.handleMousePressed(event)

                    override fun handleHyperlinkMouseMoved(event: MouseEvent) {
                        hyperlinkController.handleMouseMoved(event)
                    }

                    override fun handleHyperlinkMouseExited() {
                        hyperlinkController.handleMouseExited()
                    }

                    override fun clearHyperlinkHover() {
                        hyperlinkController.clearHyperlinkHover()
                    }

                    override fun handleSelectionMousePressed(event: MouseEvent) {
                        selectionController.handleSelectionMousePressed(event)
                    }

                    override fun handleSelectionMouseReleased(event: MouseEvent) {
                        selectionController.handleSelectionMouseReleased(event)
                    }

                    override fun handleSelectionMouseDragged(event: MouseEvent) {
                        selectionController.handleSelectionMouseDragged(event)
                    }
                },
            )
        private val renderFrameController =
            SwingRenderFrameController(
                object : SwingRenderFrameHost {
                    override val session: TerminalSession? get() = this@SwingTerminal.session
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override val settings: SwingSettings get() = this@SwingTerminal.settings
                    override val metrics: SwingMetrics get() = this@SwingTerminal.metrics
                    override val visualGeometry: TerminalVisualViewportGeometry
                        get() = this@SwingTerminal.visualGeometry
                    override val componentWidth: Int get() = this@SwingTerminal.width
                    override val componentHeight: Int get() = this@SwingTerminal.height
                    override val cursorPresentationEnabled: Boolean get() = this@SwingTerminal.cursorPresentationEnabled

                    override fun resetCursorBlinkForFrame(): Boolean = resetCursorBlinkOnEdt()

                    override fun refreshRenderCacheFromSession(session: TerminalSession) {
                        this@SwingTerminal.refreshRenderCacheFromSession(session)
                    }

                    override fun requestRender(session: TerminalSession) {
                        this@SwingTerminal.requestRenderFromSession(session)
                    }

                    override fun syncTerminalGridToActiveChrome(): Boolean =
                        this@SwingTerminal.resizeSessionToVisibleGridOnEdt(publishWhenUnchanged = false)

                    override fun clampViewport(
                        historySize: Int,
                        discardedCount: Long,
                    ): Boolean = viewportController.clamp(historySize, discardedCount, settings.scrollOnOutput)

                    override fun requestedViewportOffset(): Int = viewportController.requestedOffset

                    override fun refreshShellIntegrationDecorations(session: TerminalSession): Boolean =
                        this@SwingTerminal.refreshShellIntegrationDecorations(session)

                    override fun refreshSearchForFrame() {
                        this@SwingTerminal.searchController.refreshForFrame()
                    }

                    override fun publishViewportState(historySize: Int) {
                        this@SwingTerminal.publishViewportState(historySize)
                    }

                    override fun repaint() {
                        this@SwingTerminal.repaint()
                    }

                    override fun repaintRegion(
                        x: Int,
                        y: Int,
                        width: Int,
                        height: Int,
                    ) {
                        this@SwingTerminal.repaint(x, y, width, height)
                    }
                },
            )
        internal val cursorTimer =
            Timer(cursorTimerDelay(settings)) {
                cursorBlinkVisible = !cursorBlinkVisible
                renderFrameController.repaintBlinkState()
            }

        private val resizeListener =
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    if (resizeSessionToVisibleGridOnEdt()) {
                        session?.let(::requestRenderFromSession)
                    }
                }
            }

        private var ancestorWindow: Window? = null

        private val windowStateListener =
            WindowStateListener { event ->
                val iconified = (event.newState and Frame.ICONIFIED) != 0
                session?.terminal?.setWindowMinimized(iconified)
            }

        private fun updateMinimizedStateFromAncestor() {
            val window = ancestorWindow ?: SwingUtilities.getWindowAncestor(this)
            if (window is Frame) {
                val iconified = (window.extendedState and Frame.ICONIFIED) != 0
                session?.terminal?.setWindowMinimized(iconified)
            }
        }

        private fun handlePromptMarkerMousePressed(event: MouseEvent): Boolean {
            if (!SwingUtilities.isLeftMouseButton(event)) return false
            if (renderCache.activeBuffer == TerminalRenderBufferKind.ALTERNATE) return false
            val row = promptMarkerRowAt(event)
            if (row == NO_PROMPT_MARKER_ROW) return false
            val recordId = shellIntegrationDecorations.commandRecordIdAt(row)
            if (recordId == TerminalShellIntegrationCommandRecord.NONE) return false
            commandInteractionController.selectCommandBlock(recordId)
            event.consume()
            return true
        }

        private fun handlePromptMarkerMouseMoved(event: MouseEvent): Boolean {
            if (renderCache.activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
                updateHoveredPromptMarker(NO_PROMPT_MARKER_ROW)
                return false
            }
            val row = promptMarkerRowAt(event)
            if (row != NO_PROMPT_MARKER_ROW) hyperlinkController.clearHyperlinkHover()
            updateHoveredPromptMarker(row)
            return row != NO_PROMPT_MARKER_ROW
        }

        private fun promptMarkerRowAt(event: MouseEvent): Int {
            val paddingLeft = SwingTerminalChrome.left(settings, renderCache.activeBuffer)
            val gutterWidth = SwingTerminalChrome.promptDecorationGutterWidth(settings, renderCache.activeBuffer)
            if (gutterWidth <= 0 || event.x !in (paddingLeft - gutterWidth) until paddingLeft) {
                return NO_PROMPT_MARKER_ROW
            }
            val row = cellAt(event.x, event.y, renderCache).toInt()
            return if (shellIntegrationDecorations.hasPromptStartAt(row)) row else NO_PROMPT_MARKER_ROW
        }

        private fun updateHoveredPromptMarker(row: Int) {
            val nextCursor = if (row == NO_PROMPT_MARKER_ROW) DEFAULT_CURSOR else HAND_CURSOR
            if (hoveredPromptMarkerRow == row) {
                if (cursor !== nextCursor) cursor = nextCursor
                return
            }
            hoveredPromptMarkerRow = row
            cursor = nextCursor
            repaint()
        }

        init {
            font = settings.font
            background = Color(settings.palette.defaultBackground, true)
            foreground = Color(settings.palette.defaultForeground, true)
            isOpaque = true
            isFocusable = true
            focusTraversalKeysEnabled = false
            addFocusListener(inputController.focusListener)
            addKeyListener(inputController.keyListener)
            addMouseListener(terminalMouseListener)
            addMouseMotionListener(terminalMouseMotionListener)
            addMouseWheelListener(mouseController.wheelListener)
            addComponentListener(resizeListener)
            preferredSize = preferredGridSize(settings.columns, settings.rows)
            cursorTimer.isRepeats = true
            configureCursorTimerOnEdt()
            configureVisualBellOnEdt()
        }

        /**
         * Binds this component to [session].
         *
         * The session remains host-owned; this component only observes dirty render
         * notifications and repaints itself on the EDT. This method may be called
         * from any thread; component state is updated asynchronously on the EDT.
         *
         * @param session terminal session to display.
         */
        fun bind(session: TerminalSession) {
            runOnEdt(
                Runnable {
                    if (disposed) return@Runnable
                    bindOnEdt(session)
                },
            )
        }

        /**
         * Removes the current session binding.
         *
         * This method may be called from any thread; component state is updated
         * asynchronously on the EDT.
         */
        fun unbind() {
            runOnEdt(unbindRunnable)
        }

        /**
         * Permanently releases UI-side resources owned by this terminal component.
         *
         * Hosts should call this when a terminal tab is closed. The bound session,
         * if any, is unbound but remains host-owned. A disposed component must not
         * be rebound to another session.
         */
        fun dispose() {
            runOnEdt {
                disposeOnEdt()
            }
        }

        /**
         * Applies changed settings, rebuilding geometry only when its inputs change.
         *
         * This method may be called from any thread; component state is updated
         * asynchronously on the EDT.
         */
        fun reloadSettings() {
            runOnEdt(
                Runnable {
                    if (disposed) return@Runnable
                    reloadSettingsOnEdt()
                },
            )
        }

        /**
         * Triggers this component's visual bell indicator.
         *
         * Hosts should call this when the bound terminal session emits BEL and
         * the current host settings allow visual bell presentation. The method
         * may be called from any thread; animation state is updated on the EDT.
         */
        fun showVisualBell() {
            runOnEdt {
                visualBellController.trigger()
            }
        }

        /**
         * Returns the grid size that fits in this component's current bounds.
         *
         * This method may be called from any thread. EDT callers refresh the
         * snapshot from live component state before reading it; off-EDT callers read
         * the last EDT-published snapshot without blocking the event queue.
         *
         * @return dimension where width is columns and height is rows.
         */
        fun visibleGridSize(): Dimension {
            if (!SwingUtilities.isEventDispatchThread()) return viewportController.visibleGridSizeSnapshot()
            return viewportController.visibleGridSizeOnEdt(
                settings,
                metrics,
                width,
                height,
                renderCache.activeBuffer,
            )
        }

        /**
         * Returns the latest terminal-native scrollback viewport snapshot.
         *
         * This method may be called from any thread. EDT callers refresh the
         * snapshot from live component and render-cache state before reading it;
         * off-EDT callers read the last EDT-published snapshot without blocking.
         *
         * @return current scrollback viewport state.
         */
        fun viewportState(): TerminalViewportState {
            if (SwingUtilities.isEventDispatchThread()) {
                publishViewportState(renderCache.historySize, notifyListener = false)
            }
            return viewportController.viewportStateSnapshot()
        }

        /**
         * Returns to the live terminal viewport.
         *
         * This method may be called from any thread; component state is updated
         * asynchronously on the EDT.
         */
        fun scrollToLiveViewport() {
            scrollToScrollbackOffset(0)
        }

        /**
         * Scrolls to an absolute scrollback offset.
         *
         * The offset uses terminal-native coordinates: `0` is live output and
         * larger values move farther back into scrollback history. Values beyond
         * available history are clamped on the EDT.
         *
         * @param scrollbackOffset requested whole-row offset from live output.
         */
        fun scrollToScrollbackOffset(scrollbackOffset: Int) {
            runOnEdt {
                scrollViewportToOnEdt(scrollbackOffset)
            }
        }

        /**
         * Applies a signed scrollback delta in terminal rows.
         *
         * Positive values move farther back into scrollback; negative values move
         * toward the live viewport. Fractional values accumulate until they
         * produce a whole-row destination. Animation may render between rows,
         * but always completes on the destination row.
         *
         * @param deltaLines signed row delta.
         */
        fun scrollViewportBy(deltaLines: Double) {
            require(deltaLines.isFinite()) { "deltaLines must be finite, was $deltaLines" }
            runOnEdt {
                rowScroller.scrollByPreciseRows(deltaLines)
            }
        }

        override fun scrollFromScrollbar(
            scrollbackOffset: Int,
            valueIsAdjusting: Boolean,
        ) {
            require(scrollbackOffset >= 0) { "scrollbackOffset must be >= 0, was $scrollbackOffset" }
            if (SwingUtilities.isEventDispatchThread()) {
                scrollFromScrollbarOnEdt(scrollbackOffset, valueIsAdjusting)
                return
            }
            runOnEdt {
                scrollFromScrollbarOnEdt(scrollbackOffset, valueIsAdjusting)
            }
        }

        private fun scrollFromScrollbarOnEdt(
            scrollbackOffset: Int,
            valueIsAdjusting: Boolean,
        ) {
            if (valueIsAdjusting) {
                rowScroller.jumpToRow(scrollbackOffset)
            } else {
                rowScroller.scrollToRow(scrollbackOffset)
            }
        }

        /**
         * Scrolls to the nearest previous shell command.
         *
         * The component uses session-owned OSC 133 command metadata and reveals
         * the command's prompt-start line when present, otherwise its command
         * start line. This method may be called from any thread; component state
         * is updated asynchronously on the EDT.
         */
        fun scrollToPreviousCommand() {
            runOnEdt {
                commandInteractionController.scrollToCommand(previous = true)
            }
        }

        /**
         * Scrolls to the nearest next shell command.
         *
         * The component uses session-owned OSC 133 command metadata and reveals
         * the command's prompt-start line when present, otherwise its command
         * start line. This method may be called from any thread; component state
         * is updated asynchronously on the EDT.
         */
        fun scrollToNextCommand() {
            runOnEdt {
                commandInteractionController.scrollToCommand(previous = false)
            }
        }

        /**
         * Returns the command record at component coordinates [x], [y].
         *
         * Prompt-only rows and rows without command metadata return
         * [TerminalShellIntegrationCommandRecord.NONE]. This method is intended
         * for Swing event handlers and returns `0` when called off the EDT.
         *
         * @param x component x coordinate in pixels.
         * @param y component y coordinate in pixels.
         * @return command record id at the coordinate, or `0`.
         */
        fun commandRecordAt(
            x: Int,
            y: Int,
        ): Int {
            if (!SwingUtilities.isEventDispatchThread()) return TerminalShellIntegrationCommandRecord.NONE
            return commandInteractionController.commandRecordAt(x, y)
        }

        /**
         * Selects command output for the command under component coordinates [x], [y].
         *
         * The selection covers command output only, excluding prompt/input rows
         * for command records whose start marker was exclusive. This method is
         * intended for Swing event handlers and returns `false` off the EDT.
         *
         * @param x component x coordinate in pixels.
         * @param y component y coordinate in pixels.
         * @return true when command output was selected.
         */
        fun selectCommandOutputAt(
            x: Int,
            y: Int,
        ): Boolean = SwingUtilities.isEventDispatchThread() && commandInteractionController.selectCommandOutputAt(x, y)

        /**
         * Selects command output for [recordId].
         *
         * The selection covers command output only, excluding prompt/input rows
         * for command records whose start marker was exclusive. This method is
         * intended for Swing event handlers and returns `false` off the EDT.
         *
         * @param recordId retained command record id.
         * @return true when command output was selected.
         */
        fun selectCommandOutput(recordId: Int): Boolean =
            SwingUtilities.isEventDispatchThread() && commandInteractionController.selectCommandOutput(recordId)

        /**
         * Returns all currently retained output for [recordId].
         *
         * Soft-wrapped rows are joined while hard row boundaries become
         * newlines. This explicit EDT-only query may allocate in proportion to
         * the retained output and is never used while painting.
         *
         * @param recordId retained command record id.
         * @return command output, or `null` when unavailable or called off the EDT.
         */
        fun commandOutputText(recordId: Int): String? {
            if (!SwingUtilities.isEventDispatchThread()) return null
            return commandInteractionController.commandOutputText(recordId)
        }

        /**
         * Copies all retained output for [recordId] through the host clipboard service.
         *
         * @param recordId retained command record id.
         * @return `true` when retained output was copied; `false` when unavailable or called off the EDT.
         */
        fun copyCommandOutputToClipboard(recordId: Int): Boolean {
            if (!SwingUtilities.isEventDispatchThread()) return false
            val text = commandInteractionController.commandOutputText(recordId) ?: return false
            hostServices.clipboardHandler.copyText(text)
            return true
        }

        /**
         * Copies captured command text for [recordId] through the host clipboard service.
         *
         * @param recordId retained command record id.
         * @return `true` when command text was copied; `false` when unavailable or called off the EDT.
         */
        fun copyCommandTextToClipboard(recordId: Int): Boolean {
            if (!SwingUtilities.isEventDispatchThread()) return false
            val text = session?.shellIntegrationState?.commandText(recordId) ?: return false
            hostServices.clipboardHandler.copyText(text)
            return true
        }

        override fun addNotify() {
            super.addNotify()
            terminalFocused = isFocusOwner
            configureCursorTimerOnEdt()

            val window = SwingUtilities.getWindowAncestor(this)
            if (window != null) {
                ancestorWindow = window
                window.addWindowStateListener(windowStateListener)
                updateMinimizedStateFromAncestor()
            }
        }

        override fun removeNotify() {
            terminalFocused = false
            cursorTimer.stop()
            visualBellController.stop()
            rowScroller.finish()
            selectionController.stopSelectionDrag()
            hyperlinkDiscoveryController.reset()

            ancestorWindow?.removeWindowStateListener(windowStateListener)
            ancestorWindow = null

            super.removeNotify()
        }

        override fun doLayout() {
            super.doLayout()
            layoutShellSuggestionPopup()
        }

        private fun layoutShellSuggestionPopup() {
            val controller = shellSuggestionController ?: return
            val popup = controller.popup
            val state = controller.state()
            if (!state.visible) {
                popup.setBounds(0, 0, 0, 0)
                return
            }

            val preferred = popup.preferredSize
            val activeBuffer = renderCache.activeBuffer
            val paddingLeft = SwingTerminalChrome.left(settings, activeBuffer)
            val paddingRight = SwingTerminalChrome.right(settings, activeBuffer)
            val paddingTop = SwingTerminalChrome.top(settings, activeBuffer)
            val availableWidth = width - paddingLeft - paddingRight
            val popupWidth = minOf(availableWidth, preferred.width).coerceAtLeast(0)
            val paddingBottom = SwingTerminalChrome.bottom(settings, activeBuffer)
            val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
            if (popupWidth == 0 || availableHeight == 0 || preferred.height <= 0) {
                popup.setBounds(0, 0, 0, 0)
                return
            }

            val contentOriginY = if (visualGeometry.rowCount == renderCache.rows) visualGeometry.contentOriginY else 0.0
            val anchorColumn =
                if (state.anchorColumn in 0 until renderCache.columns) {
                    visualGeometry.bidiLayout.row(renderCache, state.anchorRow)?.visualColumn(state.anchorColumn) ?: state.anchorColumn
                } else {
                    state.anchorColumn
                }
            val anchorX = paddingLeft + anchorColumn * metrics.cellWidth
            val bottomLimit = height - paddingBottom
            val anchorTop =
                floor(paddingTop + contentOriginY + state.anchorRow * metrics.cellHeight)
                    .toInt()
                    .coerceIn(paddingTop, bottomLimit)
            val anchorBottom =
                ceil(paddingTop + contentOriginY + (state.anchorRow + 1) * metrics.cellHeight)
                    .toInt()
                    .coerceIn(paddingTop, bottomLimit)
            val spaceAbove = anchorTop - paddingTop
            val spaceBelow = bottomLimit - anchorBottom
            val placeBelow = preferred.height <= spaceBelow || spaceBelow >= spaceAbove
            val popupHeight = minOf(preferred.height, if (placeBelow) spaceBelow else spaceAbove)
            val popupY = if (placeBelow) anchorBottom else anchorTop - popupHeight
            val popupX = anchorX.coerceIn(paddingLeft, maxOf(paddingLeft, width - paddingRight - popupWidth))
            popup.setBounds(popupX, popupY, popupWidth, popupHeight)
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)

            val g = graphics.create() as Graphics2D
            try {
                if (session == null) {
                    painter.clear(g, settings.palette, width, height)
                    visualBellController.paint(g, width, height)
                    return
                }

                painter.paint(
                    g = g,
                    cache = renderCache,
                    settings = settings,
                    metrics = metrics,
                    width = width,
                    height = height,
                    cursorVisible = cursorPresentationEnabled,
                    cursorBlinkVisible = cursorBlinkVisible,
                    textBlinkVisible = cursorBlinkVisible,
                    visualGeometry = visualGeometry,
                    selection = selectionController.getViewportSelection(renderCache),
                    searchHighlights = searchController.viewportHighlights,
                    shellIntegrationDecorations = shellIntegrationDecorations,
                    hoveredPromptMarkerRow = hoveredPromptMarkerRow,
                    hyperlinkIds = hyperlinkDiscoveryController.hyperlinkIdsFor(renderCache),
                    hoveredHyperlinkId = hyperlinkController.hoveredHyperlinkId,
                    hoveredHyperlinkStartRow = hyperlinkController.hoveredHyperlinkStartRow,
                    hoveredHyperlinkStartColumn = hyperlinkController.hoveredHyperlinkStartColumn,
                    hoveredHyperlinkEndRow = hyperlinkController.hoveredHyperlinkEndRow,
                    hoveredHyperlinkEndColumn = hyperlinkController.hoveredHyperlinkEndColumn,
                    hyperlinkActivationHover = hyperlinkController.hyperlinkActivationHover,
                )
                if (hostServices.scrollbarOverlayEnabled) {
                    scrollbarOverlay.paint(
                        g = g,
                        settings = settings,
                        activeBuffer = renderCache.activeBuffer,
                        palette = renderCache.palette,
                        componentWidth = width,
                        componentHeight = height,
                        state = viewportController.viewportStateSnapshot(),
                    )
                }
                visualBellController.paint(g, width, height)
            } finally {
                g.dispose()
            }
        }

        private fun bindOnEdt(session: TerminalSession) {
            if (disposed) return
            bindingJob?.cancel(CancellationException("Terminal session binding replaced"))
            this.session = session
            updateMinimizedStateFromAncestor()
            applySettingsToSession(session, settings)
            resetScrollbackState()
            selectionController.clearSelection()
            searchController.reset(renderCache.rows)
            cancelAndHideShellSuggestionsOnEdt("Terminal session binding replaced")
            shellIntegrationDecorations.reset()
            if (hostServices.scrollbarOverlayEnabled) scrollbarOverlay.handleExited()
            visualGeometry.reset()
            selectionController.stopSelectionDrag()
            visualBellController.stop()
            lastResizedColumns = NO_RESIZE_DIMENSION
            lastResizedRows = NO_RESIZE_DIMENSION
            renderFrameController.reset()
            hyperlinkController.clearHyperlinkHover()
            hyperlinkDiscoveryController.reset()
            resizeSessionToVisibleGridOnEdt()
            bindingJob =
                componentScope.launch {
                    session.renderGeneration
                        .filter { it >= 0L }
                        .collect {
                            if (this@SwingTerminal.session === session) {
                                renderFrameController.handlePublishedFrame()
                            }
                        }
                }
            var publishedFrameAvailable = false
            session.renderPublisher.readCurrent {
                publishedFrameAvailable = true
            }
            if (publishedFrameAvailable) {
                renderFrameController.handlePublishedFrame()
            }
            requestRenderFromSession(session)
            publishViewportState(renderCache.historySize)
            repaint()
        }

        private fun unbindOnEdt() {
            bindingJob?.cancel(CancellationException("Terminal session unbound"))
            bindingJob = null
            cancelAndHideShellSuggestionsOnEdt("Terminal session unbound")
            session = null
            resetScrollbackState()
            selectionController.clearSelection()
            searchController.reset(renderCache.rows)
            shellIntegrationDecorations.reset()
            if (hostServices.scrollbarOverlayEnabled) scrollbarOverlay.handleExited()
            visualGeometry.reset()
            selectionController.stopSelectionDrag()
            lastResizedColumns = NO_RESIZE_DIMENSION
            lastResizedRows = NO_RESIZE_DIMENSION
            renderFrameController.reset()
            hyperlinkController.clearHyperlinkHover()
            hyperlinkDiscoveryController.reset()
            publishViewportState(0)
            repaint()
        }

        private fun disposeOnEdt() {
            if (disposed) return
            disposed = true
            unbindOnEdt()
            cursorTimer.stop()
            visualBellController.stop()
            rowScroller.finish()
            selectionController.stopSelectionDrag()
            hyperlinkDiscoveryController.dispose()
            suggestionJob?.cancel(CancellationException("Swing terminal disposed"))
            suggestionJob = null
            suggestionInvalidationListeners.clear()
            suggestionEligibilityListeners.clear()
            shellSuggestionController?.close()
            componentScope.cancel(CancellationException("Swing terminal disposed"))
        }

        private fun reloadSettingsOnEdt() {
            if (disposed) return
            val previous = settings
            val next = settingsProvider.currentSettings()
            if (next == previous) return

            val nextMetrics =
                if (next.font != previous.font || next.lineHeight != previous.lineHeight) buildMetrics(next) else metrics
            val geometryChanged =
                nextMetrics != metrics ||
                    next.padding != previous.padding ||
                    next.alternateScreenPadding != previous.alternateScreenPadding ||
                    next.shellIntegrationDecorationGutterWidth != previous.shellIntegrationDecorationGutterWidth
            if (geometryChanged) rowScroller.finish()
            settings = next
            metrics = nextMetrics
            if (next.font != previous.font) font = next.font
            if (next.palette != previous.palette) {
                background = Color(next.palette.defaultBackground, true)
                foreground = Color(next.palette.defaultForeground, true)
            }
            if (next.cursorBlinkMillis != previous.cursorBlinkMillis) configureCursorTimerOnEdt()
            if (next.visualBellEnabled != previous.visualBellEnabled ||
                next.visualBellColor != previous.visualBellColor ||
                next.visualBellDurationMillis != previous.visualBellDurationMillis ||
                next.visualBellEdgeThicknessPixels != previous.visualBellEdgeThicknessPixels
            ) {
                configureVisualBellOnEdt()
            }
            session?.let { applySettingsToSession(it, next, previous) }
            if (geometryChanged) {
                if (resizeSessionToVisibleGridOnEdt()) selectionController.clearSelection()
                searchController.updateViewportHighlights()
                hyperlinkController.clearHyperlinkHover()
                hyperlinkDiscoveryController.reset()
                session?.let { requestRenderFromSession(it) }
            }
            if (geometryChanged || next.columns != previous.columns || next.rows != previous.rows) {
                preferredSize = preferredGridSize(next.columns, next.rows)
                revalidate()
            }
            updateAutomaticSuggestionEligibilityOnEdt()
            repaint()
        }

        /**
         * Returns the current visible cell selection, or `null` when nothing is
         * selected.
         *
         * This method may be called from any thread. Off-EDT callers receive a
         * snapshot from the EDT.
         *
         * @return current selection, or `null`.
         */
        fun currentSelection(): CellSelection? {
            if (session == null) return null
            return selectionController.getViewportSelection(renderCache)
        }

        /**
         * Selects all retained terminal text.
         *
         * The selection spans available scrollback and the live render grid. It
         * cannot include rows already discarded by the configured scrollback
         * capacity.
         *
         * @return `true` when a bound session and non-empty render cache allowed
         * a selection to be created.
         */
        fun selectAll(): Boolean {
            if (!SwingUtilities.isEventDispatchThread()) return false
            if (session == null || renderCache.columns <= 0 || renderCache.rows <= 0) return false
            val firstAbsoluteRow = renderCache.discardedCount
            val lastAbsoluteRow = renderCache.discardedCount + renderCache.historySize + renderCache.rows - 1L
            selectionController.selectAbsoluteRows(firstAbsoluteRow, lastAbsoluteRow, renderCache.columns)
            repaint()
            return true
        }

        /**
         * Applies a literal terminal-buffer search query.
         *
         * The search covers retained scrollback plus the live grid snapshot exposed
         * through the bound session's render-frame reader. Hosts own any visible
         * search UI and call this method when their query changes.
         *
         * @param query literal text to find.
         */
        fun search(query: String) {
            runOnEdt {
                searchController.search(query)
            }
        }

        /**
         * Clears the current terminal-buffer search query and highlights.
         */
        fun clearSearch() {
            runOnEdt {
                searchController.clear()
            }
        }

        /**
         * Selects the next search result when a search query is active.
         */
        fun selectNextSearchResult() {
            runOnEdt {
                searchController.findNext()
            }
        }

        /**
         * Selects the previous search result when a search query is active.
         */
        fun selectPreviousSearchResult() {
            runOnEdt {
                searchController.findPrevious()
            }
        }

        /**
         * Configures whether terminal-buffer search is case-sensitive.
         *
         * @param caseSensitive `true` to match case exactly, `false` for
         * case-insensitive search.
         */
        fun setSearchCaseSensitive(caseSensitive: Boolean) {
            runOnEdt {
                searchController.setIgnoreCase(!caseSensitive)
            }
        }

        /**
         * Returns the current search result snapshot.
         *
         * @return current terminal search state.
         */
        fun currentSearchState(): TerminalSearchState = searchController.state()

        /**
         * Shows host-provided shell suggestions for a known command-line request.
         *
         * The reusable Swing terminal only presents the suggestions. Accepted
         * suggestions are delivered to [SwingHostServices.shellSuggestionHandler]
         * with the same [request] so host adapters can apply and learn from the
         * exact command-line replacement range that produced each suggestion.
         *
         * This is an explicit display request, so it is independent of the
         * automatic-popup setting, but requires [SwingSettings.smartSuggestionsEnabled].
         * If [suggestions] is empty, the current popup
         * is hidden.
         *
         * @param request command-line context that produced [suggestions].
         * @param suggestions suggestions to display.
         * @param selectedIndex initially selected suggestion index, or `-1` to
         * leave the popup passive until the user navigates it.
         */
        @JvmOverloads
        fun showShellSuggestions(
            request: SwingShellSuggestionRequest,
            suggestions: List<SwingShellSuggestion>,
            selectedIndex: Int = -1,
        ) {
            val snapshot = suggestions.toList()
            runOnEdt(
                Runnable {
                    if (!settings.smartSuggestionsEnabled || !isLiveViewportOnEdt()) {
                        cancelAndHideShellSuggestionsOnEdt("Suggestions are unavailable")
                        doLayout()
                        return@Runnable
                    }
                    suggestionJob?.cancel(CancellationException("Explicit suggestions replaced provider request"))
                    suggestionJob = null
                    activeSuggestionIsAutomatic = false
                    getOrCreateShellSuggestionController().show(request, snapshot, selectedIndex)
                    doLayout()
                },
            )
        }

        /**
         * Requests shell suggestions from [SwingHostServices.shellSuggestionProvider]
         * and shows the returned suggestions near a terminal-grid cell.
         *
         * The component replaces its one completion coroutine for every request,
         * so suspending providers run outside the Swing Event Dispatch Thread and
         * are cancelled when newer input arrives or the popup is hidden. This
         * automatic request is ignored when
         * [SwingSettings.smartSuggestionsEnabled] or [SwingSettings.shellSuggestionsEnabled]
         * is `false`. Empty provider
         * results hide the current popup.
         *
         * @param commandText visible command-line text known to the host.
         * @param cursorOffset UTF-16 cursor offset within [commandText].
         * @param anchorColumn visible terminal-grid column used as the popup anchor.
         * @param anchorRow visible terminal-grid row used as the popup anchor.
         */
        fun requestShellSuggestions(
            commandText: String,
            cursorOffset: Int,
            anchorColumn: Int,
            anchorRow: Int,
        ) {
            val request =
                SwingShellSuggestionRequest(
                    commandText = commandText,
                    cursorOffset = cursorOffset,
                    anchorColumn = anchorColumn,
                    anchorRow = anchorRow,
                )
            runOnEdt {
                requestShellSuggestionsOnEdt(request)
                doLayout()
            }
        }

        /**
         * Requests suggestions for the command line currently reported by the
         * bound [TerminalSession].
         *
         * This method uses OSC 133 shell-integration marker state captured by
         * the session. It does not infer command text from key events or
         * persistent command history. When no trustworthy active command line is
         * available, the current suggestion popup is hidden. This explicit
         * request remains available when automatic popup is disabled, provided
         * [SwingSettings.smartSuggestionsEnabled] is enabled.
         */
        fun requestActiveShellSuggestions() {
            runOnEdt(
                Runnable {
                    if (!settings.smartSuggestionsEnabled) {
                        cancelAndHideShellSuggestionsOnEdt("Smart suggestions disabled")
                        return@Runnable
                    }
                    val snapshot = session?.activeShellCommandLine()
                    if (snapshot == null) {
                        shellSuggestionController?.hide()
                        doLayout()
                        return@Runnable
                    }
                    val request =
                        SwingShellSuggestionRequest(
                            commandText = snapshot.commandText,
                            cursorOffset = snapshot.cursorOffset,
                            anchorColumn = snapshot.cursorColumn,
                            anchorRow = snapshot.cursorRow,
                        )
                    requestShellSuggestionsOnEdt(request, automatic = false)
                    doLayout()
                },
            )
        }

        /**
         * Hides the shell suggestion popup.
         *
         * This method may be called from any thread; component state is updated
         * asynchronously on the EDT.
         */
        fun hideShellSuggestions() {
            runOnEdt {
                cancelAndHideShellSuggestionsOnEdt("Shell suggestions hidden")
            }
        }

        /** Registers an EDT callback for shell-bound input invalidation. */
        fun addShellSuggestionInvalidationListener(listener: SwingShellSuggestionInvalidationListener) {
            suggestionInvalidationListeners += listener
        }

        /** Removes a callback previously registered with [addShellSuggestionInvalidationListener]. */
        fun removeShellSuggestionInvalidationListener(listener: SwingShellSuggestionInvalidationListener) {
            suggestionInvalidationListeners -= listener
        }

        /** Registers an EDT callback for automatic-suggestion eligibility changes. */
        fun addShellSuggestionEligibilityListener(listener: SwingShellSuggestionEligibilityListener) {
            suggestionEligibilityListeners += listener
        }

        /** Removes a callback previously registered with [addShellSuggestionEligibilityListener]. */
        fun removeShellSuggestionEligibilityListener(listener: SwingShellSuggestionEligibilityListener) {
            suggestionEligibilityListeners -= listener
        }

        /**
         * Returns whether automatic suggestions are enabled at the live viewport.
         *
         * This method is safe to call from any thread and returns the last state
         * published by the EDT without blocking it.
         */
        fun isAutomaticShellSuggestionEligible(): Boolean = automaticSuggestionEligible.get()

        /**
         * Returns the current shell suggestion popup state.
         *
         * This state is owned by Swing and must be read on the Event Dispatch
         * Thread, like the other synchronous component interaction methods.
         *
         * @return immutable shell suggestion state snapshot.
         */
        fun currentShellSuggestionState(): SwingShellSuggestionState {
            check(SwingUtilities.isEventDispatchThread()) { "shell suggestion state must be read on the EDT" }
            return shellSuggestionController?.state() ?: SwingShellSuggestionState.EMPTY
        }

        private fun applySettingsToSession(
            session: TerminalSession,
            settings: SwingSettings,
            previous: SwingSettings? = null,
        ) {
            if (settings.treatAmbiguousAsWide != previous?.treatAmbiguousAsWide) {
                session.setTreatAmbiguousAsWide(settings.treatAmbiguousAsWide)
            }
            if (settings.palette != previous?.palette) session.setThemePalette(settings.palette)
            if (settings.cursorShape != previous?.cursorShape) session.setCursorShape(settings.cursorShape)
            if (settings.pasteSanitizationPolicy != previous?.pasteSanitizationPolicy) {
                session.setPasteSanitizationPolicy(settings.pasteSanitizationPolicy)
            }
        }

        private fun handleScrollbarOverlayPressed(event: MouseEvent): Boolean {
            if (!hostServices.scrollbarOverlayEnabled) return false
            if (!isInScrollbarOverlayGutter(event)) return false
            if (!SwingUtilities.isLeftMouseButton(event)) {
                event.consume()
                return true
            }
            val handled =
                scrollbarOverlay.handlePressed(
                    x = event.x,
                    y = event.y,
                    settings = settings,
                    activeBuffer = renderCache.activeBuffer,
                    componentWidth = width,
                    componentHeight = height,
                    state = viewportController.viewportStateSnapshot(),
                ) { scrollbackOffset, valueIsAdjusting ->
                    scrollFromScrollbar(scrollbackOffset, valueIsAdjusting)
                }
            if (!handled) return false
            requestFocusInWindow()
            event.consume()
            repaint()
            return true
        }

        private fun handleScrollbarOverlayDragged(event: MouseEvent): Boolean {
            if (!hostServices.scrollbarOverlayEnabled) return false
            val handled =
                scrollbarOverlay.handleDragged(
                    y = event.y,
                    settings = settings,
                    activeBuffer = renderCache.activeBuffer,
                    componentHeight = height,
                    state = viewportController.viewportStateSnapshot(),
                ) { scrollbackOffset, valueIsAdjusting ->
                    scrollFromScrollbar(scrollbackOffset, valueIsAdjusting)
                }
            if (!handled) return false
            event.consume()
            repaint()
            return true
        }

        private fun isInScrollbarOverlayGutter(event: MouseEvent): Boolean =
            hostServices.scrollbarOverlayEnabled &&
                scrollbarOverlay.containsGutter(
                    settings = settings,
                    activeBuffer = renderCache.activeBuffer,
                    componentWidth = width,
                    componentHeight = height,
                    x = event.x,
                    y = event.y,
                )

        private fun handleScrollbarOverlayReleased(event: MouseEvent): Boolean {
            if (!hostServices.scrollbarOverlayEnabled) return false
            val handled =
                scrollbarOverlay.handleReleased(
                    y = event.y,
                    settings = settings,
                    activeBuffer = renderCache.activeBuffer,
                    componentHeight = height,
                    state = viewportController.viewportStateSnapshot(),
                ) { scrollbackOffset, valueIsAdjusting ->
                    scrollFromScrollbar(scrollbackOffset, valueIsAdjusting)
                }
            if (!handled) return false
            event.consume()
            repaint()
            return true
        }

        /**
         * Copies the current terminal text selection to the host clipboard.
         *
         * @return `true` if selection was successfully copied to clipboard, `false` otherwise.
         */
        fun copySelectionToClipboard(): Boolean {
            val boundSession = session ?: return false
            val selectedText = selectionController.getSelectedText(boundSession) ?: return false
            hostServices.clipboardHandler.copyText(selectedText)
            return true
        }

        /**
         * Writes host-approved text to the configured clipboard service.
         *
         * This is intended for host-owned workflows where policy has already
         * allowed a non-selection clipboard write, such as OSC 52 handling.
         *
         * @param text text to place on the host clipboard.
         * @return `true` when a session is bound and the clipboard handler was invoked.
         */
        fun copyTextToClipboard(text: String): Boolean {
            if (session == null) return false
            hostServices.clipboardHandler.copyText(text)
            return true
        }

        /**
         * Pastes text from the host clipboard into the active terminal session.
         *
         * @return `true` if clipboard text was read and sent to the session, `false` otherwise.
         */
        fun pasteClipboardText(): Boolean {
            val text = hostServices.clipboardHandler.readText() ?: return false
            if (text.isEmpty()) return false
            val boundSession = session ?: return false
            invalidateShellSuggestionsOnEdt()
            boundSession.encodePaste(TerminalPasteEvent(text))
            return true
        }

        private fun getOrCreateShellSuggestionController(): SwingShellSuggestionController =
            shellSuggestionController ?: SwingShellSuggestionController(
                object : SwingShellSuggestionHost {
                    override val settings: SwingSettings get() = this@SwingTerminal.settings
                    override val suggestionKeymap get() = hostServices.shellSuggestionKeymap
                    override val suggestionHandler: SwingShellSuggestionHandler get() = hostServices.shellSuggestionHandler
                    override val suggestionFeedbackHandler get() = hostServices.shellSuggestionFeedbackHandler

                    override fun revalidate() = this@SwingTerminal.revalidate()

                    override fun repaint() = this@SwingTerminal.repaint()

                    override fun requestFocusInWindow(): Boolean = this@SwingTerminal.requestFocusInWindow()

                    override fun invalidateSuggestions() {
                        invalidateShellSuggestionsOnEdt()
                    }
                },
                hostServices.shellSuggestionViewFactory,
            ).also {
                shellSuggestionController = it
                it.popup.isVisible = false
                add(it.popup)
            }

        private fun requestShellSuggestionsOnEdt(
            request: SwingShellSuggestionRequest,
            automatic: Boolean = true,
        ) {
            cancelAndHideShellSuggestionsOnEdt("Shell suggestion request replaced")
            if (!settings.smartSuggestionsEnabled || !isLiveViewportOnEdt() || automatic && !settings.shellSuggestionsEnabled) {
                return
            }
            activeSuggestionIsAutomatic = automatic
            suggestionJob =
                componentScope.launch {
                    try {
                        flow {
                            emitAll(hostServices.shellSuggestionProvider.suggestions(request))
                        }.flowOn(Dispatchers.Default)
                            .conflate()
                            .collect { suggestions ->
                                this@launch.ensureActive()
                                getOrCreateShellSuggestionController().showPreservingSelectedOutcome(
                                    request,
                                    suggestions,
                                )
                            }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (exception: Exception) {
                        System.err.println("Shell suggestion provider failed: ${exception.message}")
                        shellSuggestionController?.hide()
                    }
                }
        }

        /**
         * Requests a foreground-program screen clear/redraw.
         *
         * This sends Ctrl+L through the terminal input encoder. It deliberately
         * does not mutate the render buffer directly, because clearing the
         * emulator display behind the PTY would desynchronize shells and TUIs
         * from their cursor and prompt model.
         *
         * @return `true` when a bound session accepted the input request.
         */
        fun clearScreen(): Boolean {
            if (!SwingUtilities.isEventDispatchThread()) return false
            val boundSession = session ?: return false
            selectionController.clearSelection()
            searchController.clear()
            invalidateShellSuggestionsOnEdt()
            boundSession.encodeKey(CLEAR_SCREEN_KEY_EVENT)
            return true
        }

        private fun invalidateShellSuggestionsOnEdt() {
            cancelAndHideShellSuggestionsOnEdt("Shell suggestions invalidated by input")
            suggestionInvalidationListeners.forEach { listener ->
                runCatching(listener::onShellSuggestionsInvalidated)
            }
        }

        private fun handleContextMenuMouseEvent(
            event: MouseEvent,
            forcedByShift: Boolean,
        ): Boolean {
            val request =
                SwingTerminalContextMenuRequest(
                    terminal = this,
                    x = event.x,
                    y = event.y,
                    forcedByShift = forcedByShift,
                    hyperlink = contextHyperlinkAt(event),
                )
            return hostServices.contextMenuHandler.handleContextMenu(request)
        }

        private fun contextHyperlinkAt(event: MouseEvent): SwingTerminalContextHyperlink? {
            val hyperlinkId = hyperlinkController.hyperlinkIdAt(event)
            if (hyperlinkId == NO_HYPERLINK_ID) return null
            val uri = if (hyperlinkId > 0) session?.hyperlinkUri(hyperlinkId) else null
            return SwingTerminalContextHyperlink(
                uri = uri,
                openAction = { openHyperlink(hyperlinkId) },
                copyUriAction = {
                    uri != null && copyTextToClipboard(uri)
                },
            )
        }

        private fun isHyperlinkResolvable(hyperlinkId: Int): Boolean {
            if (hyperlinkId == NO_HYPERLINK_ID) return false
            if (hyperlinkId > 0) return session?.hyperlinkUri(hyperlinkId) != null
            return hyperlinkDiscoveryController.isDiscoveredHyperlinkResolvable(hyperlinkId, renderCache)
        }

        private fun openHyperlink(hyperlinkId: Int): Boolean {
            if (hyperlinkId == NO_HYPERLINK_ID) return false
            if (hyperlinkId > 0) {
                val uri = session?.hyperlinkUri(hyperlinkId) ?: return false
                return hostServices.hyperlinkHandler.openHyperlink(uri)
            }
            return hyperlinkDiscoveryController.openDiscoveredHyperlink(hyperlinkId, renderCache)
        }

        private fun cellAt(
            x: Int,
            y: Int,
            cache: TerminalRenderCache,
        ): Long {
            val paddingLeft = SwingTerminalChrome.left(settings, cache.activeBuffer)
            val paddingTop = SwingTerminalChrome.top(settings, cache.activeBuffer)
            val column = ((x - paddingLeft) / metrics.cellWidth).coerceIn(0, cache.columns - 1)
            val row =
                if (cache === renderCache && visualGeometry.rowCount == cache.rows) {
                    visualGeometry.rowAtComponentY(y, paddingTop)
                } else {
                    ((y - paddingTop) / metrics.cellHeight).coerceIn(0, cache.rows - 1)
                }
            val logicalColumn = visualGeometry.bidiLayout.row(cache, row)?.logicalColumn(column) ?: column
            return packCell(logicalColumn, row)
        }

        private fun repaintHyperlinkSpan(
            startRow: Int,
            startColumn: Int,
            endRow: Int,
            endColumn: Int,
        ) {
            if (startRow < 0 || endRow < startRow || renderCache.rows <= 0 || renderCache.columns <= 0) return
            val firstRow = startRow.coerceAtLeast(0)
            val lastRow = endRow.coerceAtMost(renderCache.rows - 1)
            if (firstRow > lastRow) return

            val paddingLeft = SwingTerminalChrome.left(settings, renderCache.activeBuffer)
            val paddingTop = SwingTerminalChrome.top(settings, renderCache.activeBuffer)
            val contentOriginY = if (visualGeometry.rowCount == renderCache.rows) visualGeometry.contentOriginY else 0.0
            var row = firstRow
            while (row <= lastRow) {
                val rowStartColumn = if (row == startRow) startColumn else 0
                val rowEndColumn = if (row == endRow) endColumn else renderCache.columns
                val clampedStartColumn = rowStartColumn.coerceIn(0, renderCache.columns)
                val clampedEndColumn = rowEndColumn.coerceIn(0, renderCache.columns)
                if (clampedEndColumn > clampedStartColumn) {
                    val bidi = visualGeometry.bidiLayout.row(renderCache, row)
                    val yTop = paddingTop + contentOriginY + row * metrics.cellHeight
                    val y = floor(yTop).toInt()
                    val repaintHeight = ceil(yTop + metrics.cellHeight).toInt() - y
                    forEachVisualCellSpan(bidi, clampedStartColumn, clampedEndColumn) { visualStart, visualEnd ->
                        repaint(
                            paddingLeft + visualStart * metrics.cellWidth,
                            y,
                            (visualEnd - visualStart) * metrics.cellWidth,
                            repaintHeight,
                        )
                    }
                }
                row++
            }
        }

        private fun terminalPixelYAt(
            y: Int,
            cache: TerminalRenderCache,
        ): Int {
            val paddingTop = SwingTerminalChrome.top(settings, cache.activeBuffer)
            if (cache === renderCache && visualGeometry.rowCount == cache.rows) {
                return visualGeometry.terminalPixelYAtComponentY(y, paddingTop)
            }
            val localY = y - paddingTop
            return localY.coerceIn(0, maxOf(0, cache.rows * metrics.cellHeight - 1))
        }

        private fun scrollViewportToOnEdt(
            offsetRows: Int,
            historySize: Int = renderCache.historySize,
            boundSession: TerminalSession? = session,
        ): Boolean {
            rowScroller.cancel()
            val targetRow = offsetRows.coerceIn(0, historySize)
            if (!moveViewportToOnEdt(targetRow.toDouble(), historySize, boundSession)) return false
            publishViewportState(renderCache.historySize)
            return true
        }

        private fun applyRowScrollOffsetOnEdt(
            offsetRows: Double,
            scrollComplete: Boolean,
        ): Boolean {
            val changed = moveViewportToOnEdt(offsetRows, renderCache.historySize, session)
            if (changed || scrollComplete) {
                publishViewportState(
                    renderCache.historySize,
                    notifyListener = scrollComplete,
                    notifyPrimitiveListener = !scrollComplete,
                )
            }
            return changed
        }

        private fun moveViewportToOnEdt(
            offsetRows: Double,
            historySize: Int,
            boundSession: TerminalSession?,
        ): Boolean {
            val oldRenderOffset = viewportController.requestedOffset
            val oldRequestedRows = requestedRenderRows()
            if (!viewportController.scrollTo(offsetRows, historySize)) return false

            val renderMappingChanged =
                oldRenderOffset != viewportController.requestedOffset ||
                    oldRequestedRows != requestedRenderRows()
            if (boundSession != null && renderMappingChanged) {
                requestRenderFromSession(boundSession)
            } else {
                updateVisualViewportGeometry()
            }
            repaint()
            return true
        }

        /** Returns whether the shared blink visibility changed; callers own pixel invalidation. */
        private fun resetCursorBlinkOnEdt(): Boolean {
            val wasVisible = cursorBlinkVisible
            cursorBlinkVisible = true
            if (settings.cursorBlinkMillis > 0) {
                cursorTimer.restart()
            }
            return !wasVisible
        }

        private fun configureCursorTimerOnEdt() {
            cursorTimer.delay = cursorTimerDelay(settings)
            cursorTimer.initialDelay = cursorTimer.delay
            cursorBlinkVisible = true
            if (isDisplayable && settings.cursorBlinkMillis > 0) {
                cursorTimer.restart()
            } else {
                cursorTimer.stop()
            }
        }

        private fun configureVisualBellOnEdt() {
            visualBellController.configure(
                enabled = settings.visualBellEnabled,
                colorArgb = settings.visualBellColor,
                durationMillis = settings.visualBellDurationMillis,
                edgeThicknessPixels = settings.visualBellEdgeThicknessPixels,
            )
        }

        private fun resetScrollbackState() {
            rowScroller.cancel()
            viewportController.reset()
        }

        private fun publishViewportState(
            historySize: Int,
            notifyListener: Boolean = true,
            notifyPrimitiveListener: Boolean = false,
        ) {
            viewportController.publishViewportState(
                historySize = historySize,
                visibleRows = visibleGridRows(),
                renderRows = visibleRenderRows(),
                viewportHeightPixels = viewportController.viewportPixelHeight(settings, height, renderCache.activeBuffer),
                contentHeightPixels = visualContentHeightPixels(),
                notifyListener = notifyListener,
                notifyPrimitiveListener = notifyPrimitiveListener,
            )
            updateAutomaticSuggestionEligibilityOnEdt()
        }

        private fun updateAutomaticSuggestionEligibilityOnEdt() {
            val liveViewport = isLiveViewportOnEdt()
            if (!settings.smartSuggestionsEnabled || !liveViewport || !settings.shellSuggestionsEnabled && activeSuggestionIsAutomatic) {
                cancelAndHideShellSuggestionsOnEdt(
                    if (liveViewport) "Automatic suggestions disabled by settings" else "Viewport left live output",
                )
            }
            val eligible = !disposed && settings.smartSuggestionsEnabled && liveViewport && settings.shellSuggestionsEnabled
            if (automaticSuggestionEligible.getAndSet(eligible) == eligible) return
            suggestionEligibilityListeners.forEach { listener ->
                runCatching { listener.onAutomaticShellSuggestionEligibilityChanged(eligible) }
            }
        }

        private fun isLiveViewportOnEdt(): Boolean = viewportController.preciseOffset == 0.0

        private fun cancelAndHideShellSuggestionsOnEdt(reason: String) {
            suggestionJob?.cancel(CancellationException(reason))
            suggestionJob = null
            activeSuggestionIsAutomatic = false
            shellSuggestionController?.hide()
        }

        fun preferredGridSize(
            columns: Int,
            rows: Int,
        ): Dimension =
            Dimension(
                columns * metrics.cellWidth + SwingTerminalChrome.horizontalInset(settings, TerminalRenderBufferKind.PRIMARY),
                rows * metrics.cellHeight + SwingTerminalChrome.verticalInset(settings, TerminalRenderBufferKind.PRIMARY),
            )

        private fun resizeSessionToVisibleGridOnEdt(publishWhenUnchanged: Boolean = true): Boolean {
            val visibleGridSize =
                viewportController.visibleGridSizeOnEdt(
                    settings,
                    metrics,
                    width,
                    height,
                    renderCache.activeBuffer,
                )
            val boundSession = session
            if (boundSession == null || width <= 0 || height <= 0) {
                if (publishWhenUnchanged) publishViewportState(renderCache.historySize)
                return false
            }

            val columns = visibleGridSize.width
            val rows = visibleGridSize.height
            if (columns == lastResizedColumns && rows == lastResizedRows) {
                if (publishWhenUnchanged) publishViewportState(renderCache.historySize)
                return false
            }

            rowScroller.finish()
            publishViewportState(renderCache.historySize)
            lastResizedColumns = columns
            lastResizedRows = rows

            // Animation is finished above, so resize anchoring is always row-exact.
            val oldOffset = viewportController.resizeRequestedOffset()

            val (newOffset, newHistorySize) = boundSession.resize(columns, rows, oldOffset)

            viewportController.anchorAfterResize(newOffset, newHistorySize)
            return true
        }

        private fun visibleGridRows(): Int = viewportController.visibleGridRows(settings, metrics, height, renderCache.activeBuffer)

        private fun visibleRenderRows(): Int = viewportController.visibleRenderRows(settings, metrics, height, renderCache.activeBuffer)

        private fun requestedRenderRows(): Int = viewportController.requestedRows(visibleRenderRows())

        private fun visualContentHeightPixels(): Int =
            if (visualGeometry.rowCount == renderCache.rows) {
                visualGeometry.visualHeight
            } else {
                renderCache.rows * metrics.cellHeight
            }

        private fun commandNavigationAnchorRow(): Int =
            if (visualGeometry.rowCount == renderCache.rows) {
                visualGeometry.firstFullyVisibleRow()
            } else {
                0
            }

        private fun refreshRenderCacheFromSession(session: TerminalSession) {
            session.renderPublisher.readCurrent { published ->
                renderCache.updateFrom(published)
            }
            hyperlinkDiscoveryController.scheduleForFrame()
        }

        private fun requestRenderFromSession(session: TerminalSession) {
            session.requestRender(
                scrollbackOffset = viewportController.requestedOffset,
                viewportRows = requestedRenderRows(),
            )
        }

        private fun refreshShellIntegrationDecorations(session: TerminalSession): Boolean {
            val decorationsChanged = shellIntegrationDecorations.updateFrom(session.shellIntegrationState, renderCache)
            return decorationsChanged or updateVisualViewportGeometry()
        }

        private fun updateVisualViewportGeometry(): Boolean {
            val viewportPixelHeight = viewportController.viewportPixelHeight(settings, height, renderCache.activeBuffer)
            val layoutChanged =
                visualGeometry.updateLayout(
                    metrics = metrics,
                    rows = renderCache.rows,
                    viewportPixelHeight = viewportPixelHeight,
                )
            val visualMetricsChanged =
                viewportController.updateVisualMetrics(
                    historySize = renderCache.historySize,
                    discardedCount = renderCache.discardedCount,
                    cellHeight = metrics.cellHeight,
                    visualOverflowPixels = 0,
                )
            val originChanged =
                visualGeometry.updateContentOrigin(
                    viewportController.contentOriginY(
                        cacheScrollbackOffset = renderCache.scrollbackOffset,
                        cellHeight = metrics.cellHeight,
                    ),
                )
            return layoutChanged or visualMetricsChanged or originChanged
        }

        private fun buildMetrics(settings: SwingSettings): SwingMetrics {
            val metricsSource: FontMetrics = getFontMetrics(settings.font)
            return SwingMetrics.from(metricsSource, settings.lineHeight)
        }

        private fun runOnEdt(action: Runnable) {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run()
            } else {
                hostServices.uiDispatcher.dispatch(action)
            }
        }

        private companion object {
            private const val NO_HYPERLINK_ID = 0
            private const val NO_PROMPT_MARKER_ROW = -1
            private const val NO_RESIZE_DIMENSION = -1
            private const val MIN_TIMER_DELAY_MILLIS = 1

            // One row covers a fractional component height and one covers the
            // translated leading/trailing edge during smooth row animation.
            private const val TRANSIENT_RENDER_ROW_RESERVE = 2

            private val HAND_CURSOR: Cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            private val DEFAULT_CURSOR: Cursor = Cursor.getDefaultCursor()
            private val CLEAR_SCREEN_KEY_EVENT = TerminalKeyEvent.codepoint('L'.code, TerminalModifiers.CTRL)

            private fun cursorTimerDelay(settings: SwingSettings): Int = maxOf(MIN_TIMER_DELAY_MILLIS, settings.cursorBlinkMillis)

            private fun packCell(
                column: Int,
                row: Int,
            ): Long = (column.toLong() shl 32) or (row.toLong() and 0xffff_ffffL)
        }
    }
