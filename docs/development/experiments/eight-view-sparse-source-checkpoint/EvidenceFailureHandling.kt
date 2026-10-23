package com.realityforge.scanner.hostintegration

/** Evidence serialization/write failure must not bypass owner cleanup or mask the primary error. */
internal fun preserveEvidenceFailure(primary: Throwable?, write: () -> Unit): Throwable? = try {
    write()
    primary
} catch (secondary: Throwable) {
    if (primary == null) secondary else {
        if (primary !== secondary) primary.addSuppressed(secondary)
        primary
    }
}
