"""Bundled entry point; argv[1] is the app's pinned yt-dlp (zipapp or its verified expansion)."""
import sys
from pathlib import Path

# No bytecode for this per-transfer copy. The pinned engine imported below may use the
# app's private bytecode cache (PYTHONPYCACHEPREFIX).
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).parent))
from verified_http import install
sys.dont_write_bytecode = False

zipapp = sys.argv.pop(1)
sys.path.insert(0, zipapp)
install()
from yt_dlp import main
main()
