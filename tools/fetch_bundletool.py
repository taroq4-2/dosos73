"""Download the pinned upstream bundletool with digest verification (build only)."""
import argparse
import hashlib
from pathlib import Path
import urllib.request

VERSION = '1.18.3'
SHA256 = 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29'
URL = f'https://github.com/google/bundletool/releases/download/{VERSION}/bundletool-all-{VERSION}.jar'


def fetch(destination):
    destination = Path(destination)
    if destination.exists():
        if hashlib.sha256(destination.read_bytes()).hexdigest() != SHA256:
            raise ValueError('Existing bundletool digest mismatch')
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix('.download')
    try:
        with urllib.request.urlopen(URL, timeout=120) as source, temporary.open('wb') as output:
            while block := source.read(65536):
                output.write(block)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != SHA256:
            raise ValueError('Downloaded bundletool digest mismatch')
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    fetch(parser.parse_args().output)
