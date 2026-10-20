package com.realityforge.scanner.agents.a068

import com.realityforge.scanner.viewer.loading.StrictJson
import org.junit.Assert.fail
import org.junit.Test

class StrictJsonTest {
    @Test fun acceptsStrictJsonAndEscapedKeys() {
        StrictJson.check(""" {"asset":{"version":"2.0"},"values":[-1.25e+3,true,false,null,"a\\b"]} """)
    }
    @Test fun rejectsPermissiveAndroidTokenerSyntaxAndDuplicateNames() {
        val invalid = listOf("{unquoted:1}", "{'single':1}", "{\"x\":01}", "{\"x\":NaN}",
            "{\"x\":1;\"y\":2}", "[1,]", "{\"x\":1,}", "[+1]", "[.1]", "[1.]", "[1e]",
            "{\"x\":1,\"x\":2}", "{\"a\":1,\"\\u0061\":2}", "{}{}", "//comment\n{}")
        for (text in invalid) {
            try { StrictJson.check(text); fail("Accepted invalid JSON: $text") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun rejectsDeepNestingAndControlCharacters() {
        for (text in listOf("[".repeat(34) + "0" + "]".repeat(34), "{\"x\":\"a\u0001b\"}")) {
            try { StrictJson.check(text); fail("Accepted hostile JSON") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
