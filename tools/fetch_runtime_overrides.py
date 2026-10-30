"""Fetch the pinned Readline runtime replacements; never extract arbitrary archive paths."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import tarfile
import urllib.request

ABI_ELF = {
    'armeabi-v7a': (1, 40),
    'arm64-v8a': (2, 183),
    'x86': (1, 3),
    'x86_64': (2, 62),
}
MANIFEST = Path(__file__).with_name('runtime-inputs.json')
EXPECTED_PATHS = {'usr/lib/libhistory.so.8.3', 'usr/lib/libreadline.so.8.3'}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def validate_elf(data, abi, label):
    elf_class, machine = ABI_ELF[abi]
    if (len(data) < (52 if elf_class == 1 else 64)
            or data[:4] != b'\x7fELF' or data[4] != elf_class
            or data[5] != 1 or data[6] != 1
            or int.from_bytes(data[18:20], 'little') != machine):
        raise ValueError(f'ELF class, little-endian encoding or machine mismatch for {abi}: {label}')


def load_manifest(path=MANIFEST):
    manifest = json.loads(path.read_text(encoding='utf-8'))
    entries = manifest['abis']
    if len(entries) != 4 or {x['abi'] for x in entries} != set(ABI_ELF):
        raise ValueError('Manifest must contain each of the four ABIs exactly once')
    for entry in entries:
        if not entry['url'].startswith('https://'):
            raise ValueError('Runtime inputs require HTTPS')
        files = entry['files']
        if len(files) != 2 or {x['relative_path'] for x in files} != EXPECTED_PATHS:
            raise ValueError('Manifest must contain only the two pinned Readline libraries')
        for item in files:
            if item['deb_member'] != './data/data/com.termux/files/' + item['relative_path']:
                raise ValueError('Unexpected Debian member path')
    return manifest


def ar_data_archive(data):
    if not data.startswith(b'!<arch>\n'):
        raise ValueError('Invalid Debian ar header')
    offset = 8
    archive = None
    names = set()
    while offset < len(data):
        header = data[offset:offset + 60]
        if len(header) != 60 or header[58:] != b'`\n':
            raise ValueError('Invalid ar member header')
        name = header[:16].decode('ascii').strip().rstrip('/')
        if name in names:
            raise ValueError('Duplicate ar member')
        names.add(name)
        size = int(header[48:58].decode('ascii').strip())
        offset += 60
        if size < 0 or offset + size > len(data):
            raise ValueError('Truncated ar member')
        payload = data[offset:offset + size]
        offset += size + size % 2
        if offset > len(data):
            raise ValueError('Missing ar padding')
        if name == 'data.tar.xz':
            archive = payload
    if archive is None:
        raise ValueError('Expected data.tar.xz missing')
    return archive


def read_pinned_members(package, entry):
    wanted = {x['deb_member']: x for x in entry['files']}
    found = {}
    with tarfile.open(fileobj=io.BytesIO(ar_data_archive(package)), mode='r:xz') as archive:
        for member in archive:
            if member.name not in wanted:
                continue
            item = wanted[member.name]
            if member.name in found or not member.isfile() or member.size != item['bytes']:
                raise ValueError(f'Invalid pinned member: {member.name}')
            stream = archive.extractfile(member)
            if stream is None:
                raise ValueError(f'Cannot read pinned member: {member.name}')
            payload = stream.read(item['bytes'] + 1)
            if len(payload) != item['bytes'] or sha256(payload) != item['sha256']:
                raise ValueError(f'Pinned member digest mismatch: {member.name}')
            validate_elf(payload, entry['abi'], member.name)
            found[member.name] = payload
    if set(found) != set(wanted):
        raise ValueError('Missing pinned Debian members')
    return {wanted[name]['relative_path']: payload for name, payload in found.items()}


def fetch(output, manifest):
    # Validate every download before creating any output files.
    payloads = {}
    for entry in manifest['abis']:
        request = urllib.request.Request(entry['url'], headers={'User-Agent': 'dosos73-runtime-inputs/1'})
        with urllib.request.urlopen(request, timeout=60) as response:
            if not response.geturl().startswith('https://'):
                raise ValueError('Download redirected away from HTTPS')
            package = response.read(entry['bytes'] + 1)
        if len(package) != entry['bytes'] or sha256(package) != entry['sha256']:
            raise ValueError(f"Debian input digest mismatch: {entry['abi']}")
        for relative_path, payload in read_pinned_members(package, entry).items():
            payloads[(entry['abi'], relative_path)] = payload
    root = output.resolve()
    for (abi, relative_path) in payloads:
        destination = output / abi / Path(*PurePosixPath(relative_path).parts)
        if destination.exists() or destination.is_symlink() or not destination.resolve().is_relative_to(root):
            raise ValueError(f'Output must be new and contained in output directory: {destination}')
    for (abi, relative_path), payload in payloads.items():
        destination = output / abi / Path(*PurePosixPath(relative_path).parts)
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not destination.resolve().is_relative_to(root):
            raise ValueError(f'Output escaped destination directory: {destination}')
        with destination.open('xb') as stream:
            stream.write(payload)
    print(json.dumps({'version': manifest['version'], 'verified_files': len(payloads)}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path, help='New ABI/relative_path override files are written here')
    args = parser.parse_args()
    fetch(args.output, load_manifest())


if __name__ == '__main__':
    main()
