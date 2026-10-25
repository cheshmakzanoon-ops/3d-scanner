"""Shared immutable synthetic source, with function-local copies for consumers."""
import pytest


@pytest.fixture(scope="session")
def ci_repository_template(tmp_path_factory):
    # Import at fixture execution, after normal test-module discovery. Keeping
    # this dependency in conftest also supports modules that alias candidate.
    from test_ci_evidence_validation import make_candidate_repository

    return make_candidate_repository(tmp_path_factory.mktemp("synthetic-ci-source"))
