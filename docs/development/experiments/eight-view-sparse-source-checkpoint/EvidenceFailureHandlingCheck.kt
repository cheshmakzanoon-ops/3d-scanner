package com.realityforge.scanner.hostintegration

/** Standalone JDK/Kotlin negative controls; no Android, JNI, Room or pipeline execution. */
fun main() {
    val primary = IllegalStateException("production failure")
    val writeFailure = java.io.IOException("evidence full")
    check(preserveEvidenceFailure(primary) { throw writeFailure } === primary)
    check(primary.suppressed.single() === writeFailure)
    check(preserveEvidenceFailure(null) { throw writeFailure } === writeFailure)
    check(preserveEvidenceFailure(primary) { throw primary } === primary)
    check(primary.suppressed.size == 1)
    check(preserveEvidenceFailure(primary) {} === primary)
    check(preserveEvidenceFailure(null) {} == null)
    val order = mutableListOf<String>()
    var saved: Throwable? = null
    try {
        saved = preserveEvidenceFailure(saved) { order += "evidence"; throw writeFailure }
    } finally {
        for (action in listOf("token", "bridge", "database")) order += action
    }
    check(saved === writeFailure)
    check(order == listOf("evidence", "token", "bridge", "database"))
    println("PASS: six evidence-failure helper controls; no pipeline/native execution")
}
