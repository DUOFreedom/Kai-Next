package com.kainext.duo

import com.kainext.duo.data.ModelCatalog
import kotlin.concurrent.thread

/**
 * Builds the curated model catalog on a background thread so the chat screen's first lookup
 * doesn't pay for it.
 *
 * The catalog is a lazily built map of ~1200 entries. Cold start reaches it from the chat view
 * model, which resolves the active model's context window while the first frame is composing.
 * Warming it from the application's `onCreate` gives the worker thread the whole activity
 * launch to finish instead of stalling the frame that is already being drawn.
 */
fun warmUpModelCatalogAsync() {
    thread(start = true, name = "model-catalog-warmup") { ModelCatalog.warmUp() }
}
