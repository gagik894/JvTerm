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
configured fallbacks, and then enabled system fallbacks. Emoji presentation has
an earlier host/configured emoji-font preference. All resolved fonts inherit the
configured size, and results are cached. Native color-emoji painting is a separate
platform path; it does not depend on detecting JetBrains Runtime.

These painters and caches belong to the EDT. Their scratch storage is reused and
must not be accessed concurrently. Cache-hit allocation measurements describe the
measured helper or paint path; they do not establish a zero-allocation contract
for the entire Swing component and Java2D pipeline.
