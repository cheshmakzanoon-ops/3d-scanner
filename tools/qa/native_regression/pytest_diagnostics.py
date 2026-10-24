"""Flush the first pytest failure before a later timeout can hide its summary.

This observer does not stop tests, change reports or change sanitizer settings.
The existing command runner still owns the deadline and total log-size limit.
"""
import sys

MAX_REPORT_BYTES = 64 * 1024
_reported = False


def pytest_configure(config):
    global _reported
    _reported = False


def pytest_runtest_logreport(report):
    global _reported
    if _reported or not report.failed:
        return
    _reported = True
    try:
        # Limit only the diagnostic copy, never the underlying pytest report.
        # Normal pytest rendering may allocate its text before this byte limit.
        text = ("\n=== first pytest failure (immediate diagnostic; suite continues) ===\n"
                + str(report.nodeid) + " [" + str(report.when) + "]\n"
                + report.longreprtext + "\n")
        payload = text.encode('utf-8', errors='replace')
        suffix = b'\n=== end first pytest failure ===\n'
        if len(payload) + len(suffix) > MAX_REPORT_BYTES:
            suffix = b'\n[diagnostic truncated at 64 KiB; original pytest report unchanged]\n'
        payload = payload[:MAX_REPORT_BYTES - len(suffix)] + suffix
        # Use the original stream rather than a test's temporary capture target.
        # Explicit flush preserves this copy if a later test is killed on timeout.
        stream = sys.__stdout__
        if stream is not None:
            stream.write(payload.decode('utf-8', errors='ignore'))
            stream.flush()
    except Exception:
        # Observability must not replace the original failed report with an
        # INTERNALERROR (e.g. a closed output pipe). The gate still fails normally.
        pass
