"""Load a credential-free rerun configuration; importing this starts no process."""
import json, os
from pathlib import Path
_config = os.environ.get("JUSTIN_ARTIFACT_CONFIG")
if not _config:
    raise RuntimeError("Use rerun.py prepare/run; JUSTIN_ARTIFACT_CONFIG is not set")
CONFIG = json.loads(Path(_config).read_text(encoding="utf-8"))
