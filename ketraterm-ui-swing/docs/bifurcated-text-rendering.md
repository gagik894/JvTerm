# Terminal text rendering and font fallback

`TerminalTextPainter` consumes logical cells from the copied render cache. It uses
`TerminalBidiLayout` to place them in the same visual columns as backgrounds,
overlays, cursor geometry, and pointer hit testing. Bidi changes placement and
shaping direction; it does not replace the cell's rendering policy.

## Cell and run dispatch

| Content | Rendering path |
| --- | --- |
| Compatible LTR ASCII cells | One `drawChars` call when font advances match the grid; otherwise a cached glyph vector with explicit cell positions |
| Contextual scripts and ordinary text in RTL runs | Cached, contextually shaped glyph vectors positioned by terminal-cell ownership |
| Box drawing, blocks, and other supported geometric characters | Programmatic cell primitives |
| Emoji presentation sequences | Native platform rasterizer when available, followed by Java2D fallback |
| Other scalar characters and grapheme clusters | Bounded cached `TextLayout` fallback |

ASCII batching compares foreground, font style, visibility, decorations, and
hyperlink state. Background runs are painted independently. The ASCII path does
not invoke contextual shaping or font fallback searches.

ASCII ink is clipped to the run's cell span, just like shaped text; matching
advances alone do not prevent italic or antialiased ink from crossing a style or
visibility boundary. If the caller's clip bounds already lie inside the span,
ASCII painting leaves the clip untouched. Otherwise it intersects and restores
the exact clip, including nonrectangular shapes. Those Java2D clip operations can
allocate; the contained-clip benchmark below measures the path that avoids them.

Ordinary cells and block-cursor foreground share primitive, native-emoji, and
fallback dispatch. A directional character elsewhere in a row therefore cannot
disable a block primitive or native emoji. Emoji classification happens before
access to the lazy native rasterizer.

## Native emoji image caching

`TerminalEmojiImageCache` retains up to 1,024 results for one native rasterizer.
Identity consists of code point content and raster pixel size; scalar cells and
single-code-point clusters share entries. Position, foreground color, and cell
span do not affect identity when the resulting raster size is unchanged.

Lookups reuse a key with primitive scalar fields or a borrowed cluster slice.
Only misses snapshot the content and construct text for the native rasterizer.
Borrowed arrays are released after lookup. Successful images and unsupported
null results share the same access-order LRU, so repeated unsupported text uses
Java2D fallback without retrying native work until eviction. Replacing the
painter replaces the rasterizer and its cache together.

Initialization remains synchronous. The first qualifying emoji painted through
`TerminalPlatformEmojiPainter` creates its lazy rasterizer on the calling thread;
in `SwingTerminal.paintComponent`, that is the EDT. On Windows,
`WindowsColorEmojiRasterizer.create` loads Segoe UI Emoji with `Font.createFont`
and reads and parses the font's COLR/CPAL tables. Classification prevents ordinary
Unicode and text-presentation clusters from triggering that work, but it does not
move the first emoji's font I/O or a cache miss's rasterization off the EDT.

## Contextual shaping and terminal geometry

Shaping spans are constrained by direction, font style, Unicode script, and cell
category. Foreground colors, decorations, hyperlink hover, and conceal do not
break neighboring shaping context. They determine the visible paint spans over
the resulting glyph vector.

The run buffer records the logical terminal-cell owner of every UTF-16 unit,
including surrogate pairs and combining sequences. `Font.layoutGlyphVector`
performs contextual shaping. The shaped-vector cache maps glyph character indices
back to those owners and positions complete glyph groups inside their assigned
visual cell spans. Combining-mark offsets remain relative to their base, and
ligatures retain the union of the cells they consume. Oversized groups are
compressed within their span; the whole run is never fitted as one proportional
line of text.

Large positioned vectors also retain bounded glyph batches. Uniform paint spans
draw the whole vector once; partial style spans and cursors submit only batches
whose ink can intersect their clip. This avoids resubmitting a long contextual
run for every differently styled cell. Batches preserve the original glyph codes,
positions, and transforms and are built only when the shaped run enters the cache.

The block cursor looks up the same positioned run and draws its foreground under
the cursor-cell clip. It does not shape an isolated Arabic character or reconstruct
the neighboring context from cursor colors. Concealed or hidden blinking cells
still reject foreground painting.

Each shaping window admits at most 2,048 code points (4,096 UTF-16 units), stopping
before a whole terminal cluster would exceed that budget. A longer compatible
span is processed through explicit windows until every cell has been painted.
When more text follows, the final shaped glyph cluster is retained as lookahead;
the preceding consumed cluster supplies lookbehind for the next window. These
boundaries come from the positioned glyph ownership, so a lam-alef ligature is
kept together. Row painting and cursor repaint use the same window traversal and
clip to each window's owned interval. Cached windows retain just the two cluster
boundaries needed for continuation, alongside their positioned glyphs.

A shaped cluster filling the entire budget is consumed as one window. Lookbehind
is dropped if it would prevent the next window from admitting new text. These
bounded fallbacks preserve progress and cell coverage; they cannot promise
unbounded contextual equivalence for arbitrary fonts. The scalar/cluster fallback
retains its separate protection for oversized single terminal clusters.

Cached vectors are read-only after positioning. Cache identity includes
text, cell ownership, cell span, font style, direction, and cell width; font-source,
font-generation, and font-render-context changes invalidate the cache. Repeated
lookups use reusable primitive buffers and a reusable lookup key.

## Font resolution

For ordinary text, `FontCache` tries the primary font, an optional host resolver,
configured fallbacks, and then enabled system fallbacks. Emoji presentation first
tries the host resolver, configured emoji fonts, and enabled system emoji fonts
before the ordinary pipeline. All resolved fonts inherit the configured size,
and results are cached. Native color-emoji painting is a separate platform path;
it does not depend on detecting JetBrains Runtime.

Native dispatch and font preference share `TerminalEmojiPresentation` for
scalars, primitive cluster slices, and UTF-16 text. Joiners and variation
selectors do not make ordinary script text emoji; an emoji base is required.
VS15 retains text presentation. This distinction also matters when a platform
emoji font advertises ordinary text coverage through native font substitution:
that coverage must not give it precedence over a capable primary text font.

## Allocation measurement boundaries

These painters and caches belong to the EDT in a component. Their scratch storage
must not be accessed concurrently. Unit tests verify cache reuse, invalidation,
glyph geometry, pixels, and publication ordering. Allocation measurements belong
in `ketraterm-benchmarks`, using JMH warmup, forks, and its GC profiler. They do not
use exact-byte assertions in unit tests.

Helper benchmarks own private frozen frames, painters, and graphics on one JMH
worker. Benchmarks that use EDT-bound controllers or a Swing component dispatch
fixed batches to the EDT and include the amortized dispatch cost.

| Benchmark | Measured operation and boundary |
| --- | --- |
| `TerminalTextRenderingBenchmark` | Warm style scanning and shaped-run lookup; contained-clip ASCII painting, styled Hebrew, and clipped glyph batches, with corresponding direct Java2D controls and text antialiasing on/off |
| `TerminalFontConfigurationBenchmark` | Unchanged font configuration, retained unsupported-glyph lookup, and chrome reads with zero, one, or three fallback fonts |
| `TerminalEmojiBenchmark` | Scalar/cluster cache hits for retained images and negative results; painter dispatch and direct image drawing are separate operations. Uses a recording rasterizer, excluding OS font loading and rasterization |
| `TerminalBidiBenchmark` | Cached mapping and range projection; overscan transitions include frame acceptance, with frame acceptance alone as a control |
| `TerminalRepaintBenchmark` | Search-highlight projection and damage planning; a separate operation plans two overscan frames and resets the planner |
| `TerminalScrollbarBenchmark` | Overlay painting and direct rounded-rectangle drawing, with normal/hovered thumb colors |
| `TerminalViewportPublicationBenchmark` | Primitive viewport publication and callback on the EDT; public snapshot construction is excluded |
| `TerminalSearchRefreshBenchmark` | Unchanged active search refresh on the EDT, with zero, 1,000, or 10,000 retained history rows |
| `SwingPaintBenchmark` | Complete component painting in EDT batches, including Java2D and dispatch costs; setup verifies visible content and teardown disposes the component |

Build the harness from the repository root, then pass the generated `*-jmh.jar`
from `ketraterm-benchmarks/build/libs` to Java:

```shell
./gradlew :ketraterm-benchmarks:jmhJar
java -jar <generated-jmh.jar> '.*TerminalTextRenderingBenchmark.contextualShapingCacheHit' -prof gc
```

Report `gc.alloc.rate.norm` together with the workload, JDK, platform, and fork/warmup
settings. Direct drawing controls describe Java2D costs; they are not portable
exact-allocation equality gates. CI compiles the harnesses; performance runs
remain separate from unit tests.

The single-batch drawing control submits the actual retained batch, matching
its glyph count, font, positions, transforms, and font-render context. Comparing
against the larger full run would include different Java2D work. Raster tests
preserve antialiased coverage: cursor comparisons erase the previous cell before
repainting and compare exact alpha and color. Drawing again with `SrcOver` onto
existing coverage is not an idempotent operation, even with the same glyphs.

Arabic shaping comparisons retain the exact input, including joiners. A ZWJ can
suppress Arabic ligatures, so deleting it is not a portable glyph or pixel
reference. Cache tests compare against direct Java2D shaping of the same text;
painter tests verify the complete contextual input and exact cursor restoration.

Scrollbar painting receives primitive viewport metrics directly from the EDT-owned
controller and reuses thumb geometry and colors. It does not call the public
viewport snapshot API during painting. Those snapshot objects remain part of the
host-facing query API and event handling.

The complete component paint still creates a `Graphics2D` copy and constructs a
`CellSelection` projection when the selection intersects the viewport. Java2D
clipping, transforms, and glyph drawing can also allocate outside the narrower
helper measurements or within their direct-drawing baselines. Cold font loading,
native rasterization, layout construction, and cache growth are separate from
cache-hit behavior. Session frame publication and host listener implementations
also have their own costs outside the helper benchmarks. Helper measurements do
not establish zero allocation for a complete Swing frame. The component benchmark
measures its stated static paint workload; it does not establish live-window
input latency during terminal output.
