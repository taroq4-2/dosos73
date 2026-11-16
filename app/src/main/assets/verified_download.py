"""Bundled entry point; argv[1] is the app's pinned yt-dlp zipapp."""
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).parent))
from verified_http import install

zipapp = sys.argv.pop(1)
sys.path.insert(0, zipapp)
install()
from yt_dlp import main
main()
