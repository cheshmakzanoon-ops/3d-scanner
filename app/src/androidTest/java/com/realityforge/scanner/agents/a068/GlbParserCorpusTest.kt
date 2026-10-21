package com.realityforge.scanner.agents.a068

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the same production parser corpus on Android's actual JSON runtime. */
@RunWith(AndroidJUnit4::class)
class GlbParserCorpusTest {
    @Test fun malformedAndBombCorpusFailsClosed() {
        GlbParserHostHarness.main(emptyArray())
    }
}
