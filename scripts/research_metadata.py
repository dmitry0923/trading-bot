"""Общая идентичность research-артефактов."""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def commit_sha() -> str:
    configured = os.getenv("RESEARCH_COMMIT_SHA") or os.getenv("GIT_COMMIT")
    if configured:
        return configured.strip()
    try:
        completed = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise RuntimeError(
            "Cannot determine research commit SHA; set RESEARCH_COMMIT_SHA explicitly."
        ) from exc
    value = completed.stdout.strip()
    if not value:
        raise RuntimeError("git rev-parse HEAD returned an empty commit SHA.")
    return value


def provenance(engine_version: str) -> dict:
    return {
        "engineVersion": engine_version,
        "commitSha": commit_sha(),
    }
