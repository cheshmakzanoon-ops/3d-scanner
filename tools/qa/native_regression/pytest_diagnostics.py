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


def _bounded(text, budget):
    payload = text.encode('utf-8', errors='replace')
    if len(payload) <= budget:
        return payload
    marker = b'\n[diagnostic truncated; head and tail retained]\n'
    if budget <= len(marker):
        return marker[:budget]
    head = (budget - len(marker)) // 2
    tail = budget - len(marker) - head
    return payload[:head] + marker + payload[-tail:]


def pytest_runtest_logreport(report):
    global _reported
    if _reported or not report.failed:
        return
    _reported = True
    try:
        # Limit only the diagnostic copy, never the underlying pytest report.
        # Pytest rendering may allocate its text before this byte limit.
        header = _bounded(
            "\n=== first pytest failure (immediate diagnostic; suite continues) ===\n"
            + str(report.nodeid) + " [" + str(report.when) + "]\n", 2048)
        suffix = b'\n=== end first pytest failure ===\n'
        available = MAX_REPORT_BYTES - len(header) - len(suffix)
        sections = list(getattr(report, 'sections', ()))
        # Keep traceback identity while reserving space for subprocess output,
        # which pytest stores separately from longreprtext. LSan summaries can
        # be at the very end of a large captured stderr section.
        traceback_budget = min(16 * 1024, available) if sections else available
        traceback = _bounded('--- traceback ---\n' + report.longreprtext + '\n', traceback_budget)
        payload = header + traceback
        remaining = available - len(traceback)
        for index, (name, content) in enumerate(sections):
            budget = remaining // (len(sections) - index)
            label = _bounded('\n--- ' + str(name) + ' ---\n', min(512, budget))
            section = label + _bounded(content, budget - len(label))
            payload += section
            remaining -= len(section)
        payload += suffix
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
