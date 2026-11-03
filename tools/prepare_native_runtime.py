"""Prepare a pinned Termux source rebuild; never install downloaded target code."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tarfile
import urllib.request


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def fetch(item, path):
    if not path.exists():
        with urllib.request.urlopen(item['url'], timeout=120) as source, path.open('xb') as output:
            shutil.copyfileobj(source, output)
    if digest(path) != item['sha256']:
        raise ValueError('Source digest mismatch: ' + str(path))


def replace_once(text, pattern, replacement, label):
    result, count = re.subn(pattern, lambda match: replacement, text, flags=re.MULTILINE)
    if count != 1:
        raise ValueError(f'Expected exactly one {label}, found {count}')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    lock = json.loads((root / 'tools/native-runtime-lock.json').read_text())
    args.output.mkdir(parents=True, exist_ok=True)
    cache = args.output / 'sources'
    cache.mkdir(exist_ok=True)
    archives = {}
    for name in ('framework', 'readline_framework'):
        archives[name] = cache / (name + '.tar.gz')
        fetch(lock['sources'][name], archives[name])
    # Cross-building needs a Linux interpreter of the matching Python version.
    # Build it from the same digest-pinned archive as the Android interpreter.
    fetch(lock['sources']['python'], cache / 'python.tar.xz')
    destination = args.output / 'termux'
    if destination.exists():
        raise ValueError('Use a fresh preparation directory')
    extracted = args.output / 'extracted'
    extracted.mkdir()
    with tarfile.open(archives['framework']) as archive:
        archive.extractall(extracted, filter='data')
    candidates = list(extracted.iterdir())
    if len(candidates) != 1:
        raise ValueError('Unexpected framework archive structure')
    candidates[0].rename(destination)
    # Retain the already accepted Readline/History patch level rather than
    # regressing it with the older base framework's recipe.
    with tarfile.open(archives['readline_framework']) as archive:
        for member in archive.getmembers():
            marker = '/packages/readline/'
            if marker not in member.name or not member.isfile():
                continue
            suffix = member.name.split(marker, 1)[1]
            target = destination / 'packages/readline' / suffix
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.extractfile(member).read())
    readline = destination / 'packages/readline/build.sh'
    text = readline.read_text()
    old_mirror = 'https://mirrors.kernel.org/gnu/readline/'
    if text.count(old_mirror) != 2:
        raise ValueError('Unexpected Readline source and patch URLs')
    # Use the upstream GNU server for the same pinned, checksum-verified bytes.
    readline.write_text(text.replace(old_mirror, 'https://ftp.gnu.org/gnu/readline/'))
    for package in ('python', 'openssl', 'libexpat', 'liblzma', 'libsqlite'):
        p = destination / 'packages' / package / 'build.sh'
        text = p.read_text()
        item = lock['sources'][package]
        text = replace_once(text, r'^TERMUX_PKG_VERSION=.*$', 'TERMUX_PKG_VERSION="' + item['version'] + '"', package + ' version')
        text = replace_once(text, r'^TERMUX_PKG_SHA256=.*$', 'TERMUX_PKG_SHA256=' + item['sha256'], package + ' digest')
        text = replace_once(text, r'^TERMUX_PKG_SRCURL=.*$', 'TERMUX_PKG_SRCURL=' + item['url'], package + ' URL')
        if package == 'python':
            # _tkinter is absent from every inherited ABI (required module
            # manifest). Avoid adding an unused X11/Tk build graph, not a feature.
            # The framework's build-order parser treats a quoted empty value
            # as a package name. Omit the declaration to express no dependency.
            text = replace_once(text, r'^TERMUX_PKG_BUILD_DEPENDS="tk"\n', '', 'unused Tk build dependency')
            text = replace_once(text, r'_sqlite3 _ssl _tkinter zlib', '_sqlite3 _ssl zlib', 'unused Tk module check')
        if package == 'libsqlite':
            # The upstream installation no longer leaves Tcl duplicates here.
            # Retain the existing configure flags and explicit Tcl installation.
            text = replace_once(text, r'^_SQLITE_YEAR=2025\n', '', 'unused SQLite source year')
            text = replace_once(text, r'\n\t# Remove duplicates\n\trm "\$TERMUX_PREFIX/lib"/\{pkgIndex\.tcl,libtclsqlite3\.so\}\n',
                                '', 'obsolete SQLite Tcl duplicate cleanup')
        p.write_text(text)
    # The root package's build graph also includes its subpackages' dependencies.
    # Keep the split recipe so the same pure-Python files stay out of the core.
    tkinter = destination / 'packages/python/python-tkinter.subpackage.sh'
    text = replace_once(tkinter.read_text(), r'^TERMUX_SUBPKG_DEPENDS="tcl, tk"\n',
                        '', 'unused Tk subpackage dependencies')
    tkinter.write_text(text)
    # 3.5.9 reformatted the preprocessor directive; the EGD fallback and its
    # Termux path relocation are unchanged. Keep the relocation in the patch
    # rather than dropping the patch or accepting fuzz on a crypto header.
    openssl_rand = destination / 'packages/openssl/include-crypto-rand.h.patch'
    text = openssl_rand.read_text()
    text = replace_once(text, r'^-# define DEVRANDOM_EGD ', '-#define DEVRANDOM_EGD ', 'OpenSSL old EGD directive')
    text = replace_once(text, r'^\+# define DEVRANDOM_EGD ', '+#define DEVRANDOM_EGD ', 'OpenSSL new EGD directive')
    openssl_rand.write_text(text)
    # XZ 5.8.4 adds an unrelated host case in the Android SONAME patch context.
    # Keep Termux's versioned SONAME to avoid resolving Android's liblzma.
    xz_configure = destination / 'packages/liblzma/configure.patch'
    text = replace_once(xz_configure.read_text(), r'^ linux\* \| k\*bsd\*-gnu \| kopensolaris\*-gnu \| gnu\*\)$',
                        ' linux* | ironclad*-gnu | k*bsd*-gnu | kopensolaris*-gnu | gnu*)',
                        'XZ Linux case context')
    xz_configure.write_text(text)
    sqlite = destination / 'packages/libsqlite'
    sqlite_android_patch = cache / 'sqlite-src-os_unix.c.patch'
    fetch(lock['sources']['libsqlite']['android_patch'], sqlite_android_patch)
    shutil.copyfile(sqlite_android_patch, sqlite / 'src-os_unix.c.patch')
    # SQLite 3.54.1 already accepts a target tclConfig.sh with a host tclsh.
    (sqlite / 'backport-tool-buildtclext.tcl.patch').unlink()
    # The pinned foot archive no longer matches its upstream digest. It is
    # only used to compile optional terminal descriptions, not ncurses code
    # or Python modules. Leave the checksum check enabled for every input.
    ncurses = destination / 'packages/ncurses/build.sh'
    text, count = re.subn(r'\n[ \t]+"[^\n]*x11-packages/foot/build\.sh[^\n]+\)$',
                          ')', ncurses.read_text(), flags=re.MULTILINE)
    if count != 3:
        raise ValueError(f'Expected three optional foot source references, found {count}')
    text = replace_once(text, r'^\tcp [^\n]*/full-terminfo/f/foot[^\n]*\n', '', 'foot terminfo copy')
    text = replace_once(text, r'^\t# Upstream instructions for building foot\x27s terminfo\n[^\n]*\n[^\n]*\n[^\n]*\n',
                        '', 'foot terminfo generation')
    ncurses.write_text(text)
    patch = destination / 'packages/python/0001-fix-hardcoded-paths.patch'
    text = patch.read_text()
    start = text.index('--- a/Lib/mimetypes.py\n')
    end = text.index('--- a/Lib/posixpath.py\n', start)
    # 3.12.16 indents knownfiles under the non-Windows branch for a security
    # fix. Preserve that branch and adapt only the Termux path addition.
    replacement = '''--- a/Lib/mimetypes.py
+++ b/Lib/mimetypes.py
@@ -61,6 +61,7 @@
         "/usr/local/lib/netscape/mime.types",
         "/usr/local/etc/httpd/conf/mime.types",     # Apache 1.2
         "/usr/local/etc/mime.types",                # Apache 1.3
+        "@TERMUX_PREFIX@/etc/mime.types",           # Termux
         ]
 
 inited = False
'''
    patch.write_text(text[:start] + replacement + text[end:])
    # Record every recipe/patch after the explicit overrides. The full pinned
    # framework archive is the source lock for recursively built dependencies.
    files = {}
    for p in sorted((destination / 'packages').rglob('*')):
        if p.is_file() and not p.is_symlink():
            files[p.relative_to(destination).as_posix()] = digest(p)
    proof = {'status': 'prepared, not built', 'source_lock': lock,
             'recipe_files': files, 'changed_python_patch': digest(patch),
             'rebased_openssl_egd_patch': digest(openssl_rand),
             'omitted_build_only_dependency': 'Tk: no inherited _tkinter module, no app Tk function',
             'omitted_auxiliary_source': 'foot: optional terminfo only; checksum mismatch; no ncurses library or Python module removed'}
    (args.output / 'preparation-proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    shutil.copyfile(root / 'tools/native-required-modules.json', destination / 'native-required-modules.json')
    shutil.copyfile(root / 'tools/package_native_runtime.py', destination / 'package_native_runtime.py')
    shutil.copyfile(root / 'tools/native-runtime-lock.json', destination / 'native-runtime-lock.json')
    print(destination)


if __name__ == '__main__':
    main()
