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
