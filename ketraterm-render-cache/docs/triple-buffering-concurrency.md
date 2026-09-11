# Triple-Buffered Render Cache Concurrency

The `ketraterm-render-cache` module uses a triple-buffering mechanism implemented in [TerminalRenderPublisher](../src/main/kotlin/io/github/ketraterm/render/cache/TerminalRenderPublisher.kt) to decouple the high-frequency terminal session rendering worker from UI repainting ticks.

---

## 1. The Buffering Model

At any given time, the three cache buffers (`TerminalRenderCache`) are distributed across three distinct roles:

```
            +-----------------------------------------+
            |                  Spare                  |
            |     (Unused and ready to write)         |
            +--------------------+--------------------+
                                 |
                        Acquire Writable
                                 |
                                 ▼
+---------------------+     Publish     +---------------------+
|     Back Buffer     | ──────────────► |    Front Buffer     |
|   (Writer-owned)    |                 |    (UI-readable)    |
+---------------------+                 +---------------------+
```

1. **Back Buffer (Writer-owned)**: Exclusively leased by the render worker thread. It pulls fresh row updates from the terminal frame reader.
2. **Front Buffer (UI-readable)**: Exclusively leased by the UI thread for painting and repaint planning.
3. **Spare Buffer**: Sits idle. When the writer finishes updating the back buffer, the back buffer is promoted to the front, and the previous front buffer (or the spare buffer if the front was active) is recycled as the new spare.

---

## 2. Lock-Free and Synchronized Intersections

### Lock-Free Front Queries
For quick diagnostic checks (e.g. CLI utilities, testing), [current()](../src/main/kotlin/io/github/ketraterm/render/cache/TerminalRenderPublisher.kt#L116) returns the latest front buffer atomically without locks using an `AtomicReference`.

### Leased Read Block
To prevent the front buffer from being recycled or rewritten while the UI thread is actively painting from it, the UI thread must acquire a read lease:

```kotlin
inline fun <T> readCurrent(block: (TerminalRenderCache) -> T): T?
```

* **Lease Acquisition**: Increments `readerCounts[frontIndex]` within a synchronized block.
* **UI Execution**: Passes the leased buffer safely to `block`.
* **Lease Release**: Decrements the count and signals waiting writers.

---

## 3. Source Lifetime

Row IDs and generations identify content only within one
`TerminalRenderFrameReader` instance. Both viewport reads (`updateFrom`) and
retained-range reads (`updateFromAbsoluteRange`) track that identity and force
a complete copy when the reader changes. Copying a published cache preserves
the reader identity; rotating publisher buffers does not start a new source
lifetime.

Owners call `reset()` when unbinding a source. It clears copied cells and
metadata while retaining dimensions and primitive-array capacity. Direct
`TerminalRenderFrameConsumer.accept` callers must also reset before supplying
frames from another source, because a short-lived frame does not identify its
owner.

`hasFrame` is false initially, after reset, and during an incomplete copy. It
becomes true only after the complete frame has been copied. Consumers use this
validity state to distinguish retained storage from available content. A UI
bound to a publisher with no first frame paints its empty surface and avoids
cell-dependent interaction until publication succeeds.

## 4. Allocation-Free Multi-Grapheme Clustered Text Copy

`TerminalRenderCache` optimizes grapheme cluster copies by using a packed primitive structure:
* **`clusterRefs` (LongArray)**: Packed indices mapped 1:1 to grid columns.
  * The upper 32 bits encode the starting offset in `clusterCodepoints`.
  * The lower 32 bits encode the number of codepoints in the grapheme cluster.
* **`clusterCodepoints` (IntArray)**: A single flat array containing the raw codepoints of all cached grapheme clusters.

This structure allows the cache to copy clusters from the frame and paint them on the screen without producing garbage string allocations.
