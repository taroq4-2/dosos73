"""Verify and record the files actually used by the pinned Android build."""
import argparse
import hashlib
import json
from pathlib import Path
import re


# File counts for external inputs in the pinned recipes, including their
# auxiliary downloads. libcrypt and the semaphore use in-tree sources.
CACHE_COUNTS = {
    'libandroid-support': 2, 'ncurses': 4, 'readline': 7, 'gdbm': 1,
    'libbz2': 1, 'ca-certificates': 1, 'zlib': 1, 'openssl': 1,
    'libexpat': 1, 'libffi': 1, 'liblzma': 1, 'tcl': 1,
    'libsqlite': 1, 'python': 1,
}
HOST_TRIPLES = {
    'arm64-v8a': 'aarch64-linux-android', 'armeabi-v7a': 'arm-linux-androideabi',
    'x86': 'i686-linux-android', 'x86_64': 'x86_64-linux-android',
}


def file_record(path):
    if path.is_symlink() or not path.is_file() or not path.stat().st_size:
        raise ValueError(f'Expected a nonempty regular input: {path}')
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'bytes': path.stat().st_size, 'sha256': digest}


def pinned_record(path, recipe_paths):
    record = file_record(path)
    hashes = set()
    for recipe in recipe_paths:
        hashes.update(re.findall(r'\b[0-9a-f]{64}\b', recipe.read_text()))
    if record['sha256'] not in hashes:
        raise ValueError(f'Input digest not declared in pinned recipe: {path}')
    return record


def collect(framework, build_root, ndk, prefix, host_source, abi):
    lock = json.loads((framework / 'native-runtime-lock.json').read_text())
    files = {}
    for package, expected_count in CACHE_COUNTS.items():
        cache = build_root / package / 'cache'
        candidates = sorted(cache.iterdir())
        if len(candidates) != expected_count:
            raise ValueError(f'Expected {expected_count} cached inputs for {package}, found {len(candidates)}')
        recipes = [framework / 'packages' / package / 'build.sh']
        if package == 'ncurses':
            recipes += [framework / 'x11-packages' / item / 'build.sh' for item in ('kitty', 'alacritty')]
        for path in candidates:
            record = pinned_record(path, recipes)
            if package in ('python', 'openssl', 'libexpat') and record['sha256'] != lock['sources'][package]['sha256']:
                raise ValueError(f'Input digest differs from runtime lock: {path}')
            files[path.relative_to(build_root).as_posix()] = record

    elf_cleaner = '_cache/termux-elf-cleaner'
    files[elf_cleaner] = pinned_record(build_root / elf_cleaner,
                                       [framework / 'scripts/build/termux_step_start_build.sh'])
    target = HOST_TRIPLES[abi]
    config = 'python/build/config.log'
    for name in (config, 'python/build/pyconfig.h'):
        files[name] = file_record(build_root / name)
    config_text = (build_root / config).read_text(errors='replace')
    if f'--host={target}' not in config_text or '--with-build-python=python3.12' not in config_text:
        raise ValueError('Target Python configure log does not show the requested cross-build')
    installed_config = file_record(prefix / 'include/python3.12/pyconfig.h')
    if installed_config != files['python/build/pyconfig.h']:
        raise ValueError('Installed Python header differs from the generated target header')

    cert_path = 'etc/tls/cert.pem'
    cert = pinned_record(prefix / cert_path, [framework / 'packages/ca-certificates/build.sh'])
    python_input = file_record(host_source)
    if python_input['sha256'] != lock['sources']['python']['sha256']:
        raise ValueError('Host Python source differs from the runtime lock')
    member = f'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/{target}/libc++_shared.so'
    cxx = file_record(ndk / member)
    if cxx != file_record(prefix / 'lib/libc++_shared.so'):
        raise ValueError('Installed libc++ differs from the verified NDK member')
    proof_path = framework / 'native-runtime-out' / abi / 'native-build-proof.json'
    proof = json.loads(proof_path.read_text())
    cxx_payload = [row for row in proof['payload'] if row['path'] == 'usr/lib/libc++_shared.so']
    if proof['abi'] != abi or len(cxx_payload) != 1 or any(
            cxx_payload[0].get(key) != value for key, value in cxx.items()):
        raise ValueError('Collected libc++ differs from the verified NDK member')
    ndk_properties = ndk / 'source.properties'
    return {
        'abi': abi, 'target': target, 'termux_files': files,
        'core_archive_sha256': proof['archive_sha256'],
        'core_proof': file_record(proof_path),
        'installed_ca_bundle': {'path': cert_path, **cert},
        'host_python_source': python_input,
        'ndk': {
            'archive_sha256': lock['ndk_archive_sha256'],
            'source_properties': {'content': ndk_properties.read_text(), **file_record(ndk_properties)},
            'libcxx': {'archive_member': member, 'installed_path': 'lib/libc++_shared.so', **cxx},
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--abi', choices=HOST_TRIPLES, required=True)
    parser.add_argument('--framework', type=Path, default=Path.cwd())
    parser.add_argument('--build-root', type=Path, default=Path.home() / '.termux-build')
    parser.add_argument('--ndk', type=Path, default=Path.home() / 'lib/android-ndk-r28c')
    parser.add_argument('--prefix', type=Path, default=Path('/data/data/com.termux/files/usr'))
    parser.add_argument('--host-source', type=Path, default=Path('/tmp/native-python.tar.xz'))
    args = parser.parse_args()
    print(json.dumps(collect(args.framework, args.build_root, args.ndk, args.prefix, args.host_source, args.abi),
                     indent=2))


if __name__ == '__main__':
    main()
