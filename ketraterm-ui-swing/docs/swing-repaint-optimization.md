# Swing Repaint Optimization & Selection Layout

The Swing renderer combines terminal and overlay damage before requesting
repaints, using the same viewport geometry as painting.

---

## 1. Minimal Repaint Planning (`SwingRepaintPlanner`)

The EDT-owned [SwingRepaintPlanner](../src/main/kotlin/io/github/ketraterm/ui/swing/viewport/SwingRepaintPlanner.kt)
remembers the state for which it last **scheduled** a repaint. That state includes
terminal row metadata, cursor state, and a copied viewport search projection.
The copy uses reusable primitive storage, so rebuilding the current projection
cannot overwrite the previous comparison state. Swing may coalesce pending
repaint requests before painting.

Row metadata storage follows the render cache's retained capacity, independently
of the active row count. Overscan transitions still invalidate the viewport, but
do not replace the metadata arrays. Reset invalidates the previous frame while
retaining that storage. `TerminalBidiLayout` follows the same rule: active height
does not invalidate unchanged row permutations or replace column scratch.

* **Rows:** A row is damaged when its generation or wrapping changes, or its
  search segment ranges, count, or active-result styling changes. Adjacent
  damaged rows are combined into one repaint region. Comparing search
  projections examines viewport segments, without traversing retained history.
* **Cursor:** Cursor changes repaint both old and new visual cell bounds, unless
  those rows are already covered by row damage.
* **Full surface:** Shape, buffer, and viewport mapping changes require a full
  repaint. Callers may also force one for changes such as terminal chrome.

[SwingRenderFrameController](../src/main/kotlin/io/github/ketraterm/ui/swing/render/SwingRenderFrameController.kt)
refreshes search projection before planning each published frame. Query changes,
clearing search, and result navigation use the same planner between publications;
they must also advance its scheduled-state snapshot. For example, a match across
wrapped rows `abc` / `def` for query `cde` highlights both rows. Changing only the
second row to `xef` damages both rows because the first row loses its search
segment even though its terminal generation remains unchanged.

### Smooth viewport ownership

`SwingScrollModel` owns the precise row position, history baseline and active
animation. The integer render anchor is its ceiling; overscan and fractional
translation are derived from that position. Output anchoring translates the
position and animation destination together without restarting the completion
deadline. Following output cancels motion and returns live. History shrink or
reset cancels the old timeline and settles an active viewport on a surviving row.

`SwingViewportController` owns precise-input accumulation and the Swing timer.
It reports changes through one callback: a changed anchor or overscan requirement
needs a render request, while movement inside the same render window only needs
updated geometry. Direct scrollbar dragging applies a row immediately. Reset
stops the timer and discards accumulated input; resize and metric changes finish
an animation before installing new geometry.

Translation is bounded by the rows in the installed frame. While a new render
window is pending, the current window moves only as far as its coverage allows;
it does not reset to zero merely because its anchor differs from the request.
The bound preserves the fractional bottom space below live output. Painting and
hit testing continue to consume that same installed geometry.

Published frame history is reconciled before grid resize can install a new
anchor. The session captures that anchor, history size and discarded-row count
under the resize mutation lock, so reflow discards cannot masquerade as output
when the next frame arrives. Updating cell height never rewrites the history
baseline. Frame handling
owns repainting and viewport publication for that transition, so reconciliation
does not emit a second scroll callback.

The resize result uses the active buffer's coordinates. Primary history still
reflows while the alternate buffer is active, but the alternate viewport has
zero history and offset. Core returns that coherent pair; session captures it
with the discard baseline before notifying the connector and publishing.

Viewport snapshots copy a completed EDT publication under a short monitor shared
with primitive field publication. The EDT is the sole writer and captures model
fields before entering the monitor; listener callbacks happen after it is
released. An explicit snapshot read constructs its immutable result while holding
the same monitor, without dispatching to the EDT. Animation publication and EDT
paint getters allocate no snapshot objects. A concurrent read can briefly delay
publication, so the synchronized sections contain only primitive stores or
snapshot construction, with no callbacks or event-queue work.

Scrollbar painting reads the published primitive metrics on the EDT, avoiding
an intermediate viewport snapshot. Its geometry storage and palette-derived
colors are retained. Painting and pointer interaction share the thumb geometry
calculation, including tracks shorter than the normal minimum thumb height.

---

## 2. Selection Drag Matrix & Text Extraction

Selection handles selection sweeps, word-level highlights, and block selections:

* **Sweep Selection**: Uses logical text columns. Each row's bidi mapping projects
  the selected logical cells into visual spans for painting.
* **Block Selection**: Keeps horizontal bounds in visual columns, independently
  of each row's text direction. Vertical viewport clipping changes only the row
  bounds. Painting uses the visual interval directly; copying visits the selected
  cells in logical order on each row and preserves row breaks. Intersected wide
  cells and grapheme clusters are included whole. The drag retains both logical
  and visual anchor columns so changing Alt while the anchor is offscreen does
  not reinterpret its coordinate.
* **Smart Word & Path Expansion**:
  * **Standard Words**: Double-clicking a cell expands the selection left and right to contiguous letters, numbers, and underscores.
  * **Paths / URIs**: If the clicked sequence contains directory slash markers (`/`, `\`), dot indicators (`.`), or colon signs (`:`), the text extractor expands the selection across path-safe characters, allowing users to easily select full file paths or URLs.

---

## 3. Clipboards & Key Mapping Services

* **Clipboard Handlers**: Integrates with standard OS clipboards using [SwingHostServices](../src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingHostServices.kt), sanitizing carriage returns during copy/paste based on policy options.
* **Focus Mapping**: Converts window focus gain/loss into `TerminalFocusEvent` events, routing them to the active session to trigger bracketed focus reports (`CSI I` and `CSI O`).
