#!/usr/bin/env python3
"""Offline APK/source ZIP leakage checks. Never prints matched content or credentials.

Usage: python scripts/verify-public-artifacts.py --apk dist/app.apk --source dist/source.zip
       python scripts/verify-public-artifacts.py --self-test
Only Python's standard library is used. Archives are inspected without extraction.
"""
import argparse
import hashlib
import io
import ipaddress
import json
import math
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import string
import sys
import unittest
from unittest import mock
import zipfile

MAX_ENTRY = 64 * 1024 * 1024
MAX_TOTAL = 350 * 1024 * 1024
ROOT_FILES = {'.gitignore', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
              'app/build.gradle.kts', 'LICENSE', 'README.md'}
ROOT_DIRS = ('app/src/', 'scripts/', '.github/', 'docs/', 'design/')
PRIVATE_DIRS = {'.git', '.tooling', '.gradle', '.kotlin', 'inspection', 'verification',
                'captures', 'shared_prefs', 'databases', 'cache', 'code_cache', 'generated', 'build', '__pycache__'}
PRIVATE_FILES = {'local.properties', 'pov_accounts_encrypted.xml', 'playback.xml', 'pov_appearance.xml'}
PRIVATE_SUFFIXES = {'.jks', '.keystore', '.p12', '.pfx', '.key', '.log', '.hprof', '.pcap', '.pcapng', '.db', '.sqlite', '.pyc'}
IPV4 = re.compile(r'(?<![\w.])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![\w.])')
OPAQUE = re.compile(r'[A-Za-z0-9_\-]{32,256}')
QUOTED = re.compile(r'''["']([A-Za-z0-9_\-]{32,256})["']''')
JWT = re.compile(r'eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}')
KNOWN_TOKEN = re.compile(r'(?:github_pat_|gh[pousr]_)[A-Za-z0-9_]{20,}')
CIPHER = re.compile(r'v1:[A-Za-z0-9+/]{12,}={0,2}:[A-Za-z0-9+/]{20,}={0,2}')
PEM = re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----')
AUTH_VALUE = re.compile(r'''(?ix)(?:bearer\s+|[?&](?:token|access_token|refresh_token|api_key|client_secret|client_id)=)([A-Za-z0-9_\-%]{8,})''')
CONFIG_VALUE = re.compile(r'''(?ix)["'](?:trakt\.|torbox\.)?(?:client_id|access_token|refresh_token|api_key|client_secret|token)["']\s*(?:[:,=]|\bto\b)\s*["']([^"'\r\n]+)["']''')
XML_CONFIG = re.compile(r'''(?ix)<string\b[^>]*\bname\s*=\s*["'](?:trakt[._]|torbox[._])?(?:client_id|access_token|refresh_token|api_key|client_secret|token)["'][^>]*>\s*([^<]+)</string>''')
URL = re.compile(r'https?://[^\s<>"\x27]+')
PUBLIC_FIXTURE = re.compile(r'(?i)^(?:test[-_]|synthetic[-_]|fixture[-_]|example[-_]|fake[-_])')
SAFE_TEST_VALUES = {'synthetic', 'private-value', 'do-not-expose', 'access-refreshed'}
PUBLIC_ALPHABETS = {string.ascii_uppercase + string.ascii_lowercase + string.digits + '-_',
                    string.ascii_uppercase + string.ascii_lowercase + string.digits,
                    string.digits + string.ascii_lowercase + string.ascii_uppercase,
                    string.ascii_lowercase + string.ascii_uppercase + string.digits}
# Reviewed public source provenance for org.videolan.android:libvlc-all:3.7.7.
# Evidence: the matching official libvlcjni build.gradle and buildsystem/get-vlc.sh,
# recorded with the artifact's resource revisions in docs/LIBVLC.md. These are URL-
# scoped source commits, not general exemptions for identical hexadecimal values.
PUBLIC_SOURCE_URL_ROOTS = (
    'https://code.videolan.org/videolan/libvlcjni/-/tree/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6',
    'https://code.videolan.org/videolan/libvlcjni/-/blob/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6',
    'https://code.videolan.org/videolan/vlc/-/tree/84177b2273abc5c4300234e6e18b55a4d2dd5a03',
)
# Exact official source archives downloaded, bounded, hashed and inspected for
# docs/LIBVLC.md. An archive filename, revision, host, suffix or query change is
# not covered. This does not exempt either revision as a standalone literal.
PUBLIC_SOURCE_ARCHIVE_URLS = (
    'https://code.videolan.org/videolan/libvlcjni/-/archive/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6/libvlcjni-0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6.tar.gz',
    'https://code.videolan.org/videolan/vlc/-/archive/84177b2273abc5c4300234e6e18b55a4d2dd5a03/vlc-84177b2273abc5c4300234e6e18b55a4d2dd5a03.tar.gz',
)
# Exact APK bytes compared with the unmodified official Maven AAR below. The AAR
# has SHA256 b48dab96e0e90e34cce3861963500c144a5aadb1b249d501d6a4b104d849e61b.
# https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/libvlc-all-3.7.7.aar
# Native parser markers/symbols and Lua's public third-party client identifiers are
# upstream library content, not the app owner's account data. A changed file is
# a failure AND remains subject to the normal content checks. These pins do not
# authorize source files, different archive paths, or other dependency versions.
REVIEWED_UPSTREAM_APK = {
    'lib/arm64-v8a/libc++_shared.so': '0c52cfab2df0d957' 'd8b346a2bdc5ae8d' '71feca2591924d77' 'e1cd724d5bf74352',
    'lib/arm64-v8a/libvlc.so': '4d5bb84df69c876d' 'a7dbc13555b9243b' 'fc77666b2893a562' '52eb15af033774c4',
    'lib/arm64-v8a/libvlcjni.so': '599962b01292c011' '56ff876e806cb95b' '9e249ccf704c5c2c' 'eb56ff73e1b70f83',
    'lib/armeabi-v7a/libc++_shared.so': '092a44574c247982' 'e265743acb1227e1' '1a2f079a765814fe' 'f1b627dbfb2b79d5',
    'lib/armeabi-v7a/libvlc.so': '30ae77c864280778' 'fa8bc79d8ebae62a' '5a2ecd23fbef439b' '4d961e1ded76abef',
    'lib/armeabi-v7a/libvlcjni.so': '94ae498ac406e106' '5a947b7e7b5ffd06' '482ca2b3542d69a1' '6c89da0dc14bff0b',
    'lib/x86/libc++_shared.so': '3051dd001301b810' '24779099e11f657f' '10e983d02aa1b16f' '18e711ec33685314',
    'lib/x86/libvlc.so': '6cd9e82c2a1d39de' '0ecf8cec05fa215d' '51830fa3fdb75f3e' '2a7d7381ff486f8c',
    'lib/x86/libvlcjni.so': 'c47ee2e337ee7116' '9d0b99926334e9d7' 'bc5f8a2e67d12715' '6a54a2e9f726af8a',
    'lib/x86_64/libc++_shared.so': 'e28cb9e0f456caf8' '498d0e8562b3ac3f' '73bf78c07555a52b' '5602396f240abec3',
    'lib/x86_64/libvlc.so': '345d0030ed9568d2' '138fda86dd2a6df5' '48be02149dd29dc9' '77a29d2a482f0801',
    'lib/x86_64/libvlcjni.so': '4ecfc592a1226345' '056cd94805acbee9' 'd8020fafb10ab8eb' 'c37bcad3d79a2102',
    'assets/lua/playlist/soundcloud.lua': 'ba38d814dc6d75a0' 'fa80f555fce132b8' '6d8bb01fa3dced6f' '232e49f185931a3d',
    'assets/lua/playlist/twitch.lua': '51a1a1ad502cf313' 'd1befce19040fff3' '6889038b81a1d1fb' 'b815e808cfdd34a2',
}

# Reviewed synthetic network fixtures from the Cameo 0.4.0 JVM tests. These use
# injected/mock transports, documentation-range rejection cases, or ephemeral
# loopback servers; they are not addresses collected from a user's devices.
# Only the IP-address finding is classified, only at the exact source path,
# and only for this reviewed UTF-8 content. CRLF-to-LF normalization allows a
# Windows checkout without accepting any other content change. All credential,
# archive-path, and runtime-file checks remain active. Changed content requires
# another review and remains subject to the normal scan.
REVIEWED_NETWORK_TEST_SOURCES = {
    'app/src/test/java/dev/pov/tv/DownloadTransferTest.kt': 'bb50bc78a39074de' '1e68150caf002f9b' '2eb5e88654c22e38' 'f72d9271cf2a1076',
    'app/src/test/java/dev/pov/tv/SameOriginMediaRequestsTest.kt': '1571fbb5f6880094' '924dd47c58619121' '2b0dfc98ef494f47' '4a65a11842bdfa80',
    'app/src/test/java/dev/pov/tv/data/AddonRepositoryTest.kt': 'ab323e82b3e21cd0' '53a6367a48fdd19f' '258e3469e101324b' '18bedb0fac3ac3d5',
    'app/src/test/java/dev/pov/tv/data/CameoConnectionsTest.kt': 'f51b2366c36d37d6' '81bee5127c5ed2fd' 'dc02783606692cc7' 'b3e70223c788279c',
    'app/src/test/java/dev/pov/tv/data/CameoServiceTransportTest.kt': '3ea9bd56f831c2d3' '458eb1ae295212c6' '476dbace1a9a781f' '30992334c6ed3d84',
    'app/src/test/java/dev/pov/tv/data/ProviderHubTest.kt': '2f610784a996799f' 'd6f69977c9b76cd9' '17ebb6fb49110419' '4273a37bdc542420',
    'app/src/test/java/dev/pov/tv/data/SourceResolverEndpointTest.kt': '2199f8551e217cf7' 'a89a22701d5a7751' '9ae7ddd41ebb3086' '3e2145c19cebca15',
    'app/src/test/java/dev/pov/tv/subtitles/AddonSubtitleDownloadsTest.kt': 'a09f8fa1a116a15d' '710e8ddf80dcee0f' '67724bb27880ccaf' '6d88b4edcef9d929',
}


def public_source_revision(url):
    clean = url.rstrip(').,;]')
    if '?' in clean or '#' in clean:
        return None
    if clean in PUBLIC_SOURCE_ARCHIVE_URLS:
        return clean.rsplit('/', 2)[-2]
    for root in PUBLIC_SOURCE_URL_ROOTS:
        if clean == root or clean.startswith(root + '/'):
            return root.rsplit('/', 1)[-1]
    return None


def safe_name(name):
    name = IPV4.sub('[address]', name)
    return OPAQUE.sub('[long-segment]', name)[:240]


def finding(rule, name, line=None):
    result = {'rule': rule, 'entry': safe_name(name)}
    if line is not None:
        result['line'] = line
    return result


def entropy(value):
    return -sum((value.count(c) / len(value)) * math.log2(value.count(c) / len(value)) for c in set(value))


def opaque_value(value, public_digests, sensitive=False):
    if PUBLIC_FIXTURE.match(value) or value in public_digests or value in PUBLIC_ALPHABETS:
        return False
    if not OPAQUE.fullmatch(value):
        return False
    if re.fullmatch(r'[a-fA-F0-9]{40}|[a-fA-F0-9]{64}', value):
        return entropy(value) >= 3.2
    classes = sum(bool(re.search(pattern, value)) for pattern in ('[a-z]', '[A-Z]', '[0-9]'))
    return classes == 3 and entropy(value) >= (4.1 if sensitive else 4.5)


def scan_text(text, name, public_digests, dex=False, binary=False, reviewed_network_fixture=False):
    issues = []
    # The API and preference *field names* are public implementation details, not secrets.
    for number, line in enumerate(text.splitlines(), 1):
        rules = set()
        for match in IPV4.finditer(line):
            try:
                address = ipaddress.ip_address(match.group())
                # Dependency DEX contains literal wildcard/localhost defaults. These exact
                # standalone defaults do not identify the owner's LAN or account.
                dependency_default = dex and line == match.group() and (address.is_loopback or address.is_unspecified)
                if (address.is_private or address.is_loopback or address.is_link_local) and not (dependency_default or reviewed_network_fixture):
                    rules.add('private-network-address')
            except ValueError:
                pass
        if PEM.search(line):
            rules.add('private-key-material')
        if JWT.search(line) or KNOWN_TOKEN.search(line):
            rules.add('recognized-secret-format')
        if CIPHER.search(line):
            rules.add('runtime-encrypted-account-value')
        if any(len(m.group(1)) >= 24 and not PUBLIC_FIXTURE.match(m.group(1)) and m.group(1) not in SAFE_TEST_VALUES and '$' not in m.group(1) for m in AUTH_VALUE.finditer(line)):
            rules.add('literal-authorization-value')
        for match in list(CONFIG_VALUE.finditer(line)) + list(XML_CONFIG.finditer(line)):
            value = match.group(1)
            if len(value) >= 24 and not PUBLIC_FIXTURE.match(value) and '$' not in value and '{' not in value:
                if opaque_value(value, public_digests, sensitive=True):
                    rules.add('embedded-account-configuration')
        candidates = [line] if dex else [m.group() for m in OPAQUE.finditer(line)] if binary else [m.group(1) for m in QUOTED.finditer(line)]
        if any(opaque_value(value, public_digests) for value in candidates):
            rules.add('opaque-literal-needs-review')
        for match in URL.finditer(line):
            reviewed_revision = public_source_revision(match.group())
            if any(part != reviewed_revision and opaque_value(part, public_digests)
                   for part in re.split(r'[/?:&=]', match.group().rstrip(').,;]'))):
                rules.add('opaque-url-configuration')
        issues.extend(finding(rule, name, number) for rule in sorted(rules))
    return issues


def dex_strings(data):
    """Read the DEX string table; string literals remain visible without a decompiler."""
    if len(data) < 112 or not data.startswith(b'dex\n'):
        raise ValueError('Invalid DEX header')
    count, offset = struct.unpack_from('<II', data, 56)
    if count > 2_000_000 or offset + count * 4 > len(data):
        raise ValueError('Invalid DEX string table')
    # Class descriptors, prototype shorties and member names are compiler metadata,
    # not application configuration. Excluding their exact indices avoids treating
    # long camelCase methods with numeric suffixes as random account credentials.
    metadata = set()
    for size_offset, width, string_offset in ((64, 4, 0), (72, 12, 0), (80, 8, 4), (88, 8, 4)):
        size, table = struct.unpack_from('<II', data, size_offset)
        if size > 2_000_000 or table + size * width > len(data):
            raise ValueError('Invalid DEX metadata table')
        for index in range(size):
            metadata.add(struct.unpack_from('<I', data, table + index * width + string_offset)[0])
    values = []
    for index in range(count):
        cursor, = struct.unpack_from('<I', data, offset + index * 4)
        for _ in range(5):
            if cursor >= len(data):
                raise ValueError('Invalid DEX string offset')
            value = data[cursor]
            cursor += 1
            if value < 128:
                break
        else:
            raise ValueError('Invalid DEX string length')
        end = data.find(b'\0', cursor)
        if end < 0:
            raise ValueError('Unterminated DEX string')
        if index not in metadata:
            values.append(data[cursor:end].decode('utf-8', errors='replace'))
    return values


def entry_issues(name, kind):
    clean = name.replace('\\', '/')
    path = PurePosixPath(clean)
    issues = []
    if clean.startswith('/') or '..' in path.parts or re.match(r'^[A-Za-z]:', clean):
        issues.append(finding('unsafe-archive-path', name))
    parts = {part.lower() for part in path.parts}
    forbidden_parts = PRIVATE_DIRS - {'build'} if kind == 'apk' else PRIVATE_DIRS
    if parts & forbidden_parts or path.name.lower() in PRIVATE_FILES or path.suffix.lower() in PRIVATE_SUFFIXES or path.name.startswith('.env'):
        issues.append(finding('private-or-runtime-file', name))
    if kind == 'source' and clean not in ROOT_FILES and not clean.startswith(ROOT_DIRS):
        issues.append(finding('outside-source-allowlist', name))
    return issues


def public_hashes(source):
    # These are explicit tool download integrity pins, not a blanket exemption for hex secrets.
    values = set()
    with zipfile.ZipFile(source) as archive:
        for name in archive.namelist():
            if name.replace('\\', '/') == 'scripts/bootstrap.ps1':
                if archive.getinfo(name).file_size > MAX_ENTRY:
                    continue
                text = archive.read(name).decode('utf-8-sig', errors='replace')
                for line in text.splitlines():
                    if re.match(r"^\s*Get-VerifiedFile\s+'https://", line):
                        values.update(re.findall(r"'([a-fA-F0-9]{64})'", line))
    return values


def audit_archive(path, kind, public_digests=()):
    raw = path.getvalue() if isinstance(path, io.BytesIO) else Path(path).read_bytes()
    name = 'synthetic.zip' if isinstance(path, io.BytesIO) else Path(path).name
    result = {'kind': kind, 'file': safe_name(name), 'sha256': hashlib.sha256(raw).hexdigest(),
              'entries': 0, 'dex_strings': 0, 'reviewed_upstream_entries': [],
              'reviewed_network_test_entries': [], 'issues': []}
    public_digests = set(public_digests)
    try:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            names = [info.filename.replace('\\', '/') for info in infos if not info.is_dir()]
            result['entries'] = len(names)
            if len(set(names)) != len(names):
                result['issues'].append(finding('duplicate-archive-entry', '[archive]'))
            if sum(info.file_size for info in infos) > MAX_TOTAL:
                result['issues'].append(finding('archive-too-large-to-audit', '[archive]'))
                return result
            required = {'AndroidManifest.xml', 'classes.dex'} if kind == 'apk' else {'app/src/main/AndroidManifest.xml', 'build.gradle.kts', 'LICENSE', 'docs/PRIVACY.md'}
            for name in sorted(required - set(names)):
                result['issues'].append(finding('required-entry-missing', name))
            for info in infos:
                if info.is_dir():
                    continue
                name = info.filename
                result['issues'].extend(entry_issues(name, kind))
                if stat.S_ISLNK(info.external_attr >> 16):
                    result['issues'].append(finding('archive-symlink', name))
                if info.flag_bits & 1 or info.file_size > MAX_ENTRY:
                    result['issues'].append(finding('entry-cannot-be-audited', name))
                    continue
                data = archive.read(info)
                reviewed_network_fixture = False
                if kind == 'source' and name in REVIEWED_NETWORK_TEST_SOURCES:
                    normalized_digest = hashlib.sha256(data.replace(b'\r\n', b'\n')).hexdigest()
                    if normalized_digest == REVIEWED_NETWORK_TEST_SOURCES[name]:
                        reviewed_network_fixture = True
                        result['reviewed_network_test_entries'].append({
                            'entry': name, 'sha256': hashlib.sha256(data).hexdigest(),
                            'lf_normalized_sha256': normalized_digest,
                            'provenance': 'Reviewed synthetic/mock endpoint or loopback-server JVM test fixture.',
                        })
                    else:
                        result['issues'].append(finding('reviewed-network-fixture-content-changed', name))
                if kind == 'apk' and name in REVIEWED_UPSTREAM_APK:
                    digest = hashlib.sha256(data).hexdigest()
                    if digest == REVIEWED_UPSTREAM_APK[name]:
                        result['reviewed_upstream_entries'].append({'entry': name, 'sha256': digest,
                            'provenance': 'Unmodified official org.videolan.android:libvlc-all:3.7.7 AAR entry.'})
                        continue
                    result['issues'].append(finding('reviewed-upstream-bytes-changed', name))
                if name.lower().endswith('.dex'):
                    try:
                        values = dex_strings(data)
                        result['dex_strings'] += len(values)
                        for value in values:
                            result['issues'].extend(scan_text(value, name, public_digests, dex=True))
                    except (ValueError, struct.error):
                        result['issues'].append(finding('invalid-dex-cannot-be-audited', name))
                else:
                    binary = b'\0' in data
                    # ASCII strings also cover binary XML/resource UTF-8 string pools and metadata.
                    for block in re.findall(rb'[\x20-\x7e\r\n\t]{8,}', data):
                        result['issues'].extend(scan_text(block.decode('ascii'), name, public_digests, binary=binary,
                                                         reviewed_network_fixture=reviewed_network_fixture))
                    # Android binary resources can use UTF-16LE string pools.
                    for block in re.findall(rb'(?:[\x20-\x7e]\x00){8,}', data):
                        result['issues'].extend(scan_text(block.decode('utf-16le'), name, public_digests, binary=True,
                                                         reviewed_network_fixture=reviewed_network_fixture))
    except (zipfile.BadZipFile, RuntimeError, OSError, ValueError):
        result['issues'].append(finding('archive-could-not-be-fully-read', '[archive]'))
    # Content can appear in several encodings/string tables. Report each location/rule once.
    result['issues'] = list({json.dumps(issue, sort_keys=True): issue for issue in result['issues']}.values())
    return result


class SyntheticTests(unittest.TestCase):
    @staticmethod
    def source_fixture_archive(name, contents):
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w') as output:
            for required in ('app/src/main/AndroidManifest.xml', 'build.gradle.kts', 'LICENSE', 'docs/PRIVACY.md'):
                output.writestr(required, 'synthetic required entry')
            output.writestr(name, contents)
        archive.seek(0)
        return archive

    def test_reviewed_network_fixture_requires_matching_source_path_and_content(self):
        name = 'app/src/test/java/example/SyntheticNetworkTest.kt'
        fixture = ('val address = "' + '.'.join(map(str, [192, 168, 44, 2])) + '"\n').encode()
        digest = hashlib.sha256(fixture).hexdigest()
        with mock.patch.dict(REVIEWED_NETWORK_TEST_SOURCES, {name: digest}):
            result = audit_archive(self.source_fixture_archive(name, fixture), 'source')
        self.assertEqual([], result['issues'])
        self.assertEqual([digest], [x['sha256'] for x in result['reviewed_network_test_entries']])

    def test_reviewed_network_fixture_accepts_only_crlf_line_ending_variation(self):
        name = 'app/src/test/java/example/SyntheticNetworkTest.kt'
        fixture = ('val address = "' + '.'.join(map(str, [127, 0, 0, 1])) + '"\n').encode()
        digest = hashlib.sha256(fixture).hexdigest()
        with mock.patch.dict(REVIEWED_NETWORK_TEST_SOURCES, {name: digest}):
            result = audit_archive(self.source_fixture_archive(name, fixture.replace(b'\n', b'\r\n')), 'source')
            altered = audit_archive(self.source_fixture_archive(name, fixture + b' '), 'source')
        self.assertEqual([], result['issues'])
        self.assertEqual(digest, result['reviewed_network_test_entries'][0]['lf_normalized_sha256'])
        self.assertTrue(any(x['rule'] == 'reviewed-network-fixture-content-changed' for x in altered['issues']))

    def test_changed_network_fixture_keeps_address_and_secret_findings(self):
        name = 'app/src/test/java/example/SyntheticNetworkTest.kt'
        fixture = ('val address = "' + '.'.join(map(str, [10, 20, 30, 40])) + '"\n').encode()
        changed = fixture + ('Bearer ' + 'opaque-' * 8).encode()
        with mock.patch.dict(REVIEWED_NETWORK_TEST_SOURCES, {name: hashlib.sha256(fixture).hexdigest()}):
            result = audit_archive(self.source_fixture_archive(name, changed), 'source')
        self.assertEqual([], result['reviewed_network_test_entries'])
        rules = {x['rule'] for x in result['issues']}
        self.assertTrue({'reviewed-network-fixture-content-changed', 'private-network-address',
                         'literal-authorization-value'} <= rules)

    def test_network_fixture_classification_cannot_move_to_another_path_or_apk(self):
        name = 'app/src/test/java/example/SyntheticNetworkTest.kt'
        fixture = ('val address = "' + '.'.join(map(str, [192, 168, 44, 2])) + '"\n').encode()
        with mock.patch.dict(REVIEWED_NETWORK_TEST_SOURCES, {name: hashlib.sha256(fixture).hexdigest()}):
            moved = audit_archive(self.source_fixture_archive('app/src/main/assets/copied.kt', fixture), 'source')
            packaged = audit_archive(self.source_fixture_archive(name, fixture), 'apk')
        for result in (moved, packaged):
            self.assertEqual([], result['reviewed_network_test_entries'])
            self.assertTrue(any(x['rule'] == 'private-network-address' for x in result['issues']))

    def test_reviewed_network_fixture_does_not_exempt_other_secret_rules(self):
        name = 'app/src/test/java/example/SyntheticNetworkTest.kt'
        fixture = ('.'.join(map(str, [127, 0, 0, 1])) + '\nBearer ' + 'opaque-' * 8 +
                   '\n-----BEGIN ' + 'PRIVATE KEY-----').encode()
        with mock.patch.dict(REVIEWED_NETWORK_TEST_SOURCES, {name: hashlib.sha256(fixture).hexdigest()}):
            result = audit_archive(self.source_fixture_archive(name, fixture), 'source')
        self.assertEqual(1, len(result['reviewed_network_test_entries']))
        rules = {x['rule'] for x in result['issues']}
        self.assertNotIn('private-network-address', rules)
        self.assertTrue({'literal-authorization-value', 'private-key-material'} <= rules)

    def test_field_names_dynamic_values_and_public_fixtures_are_allowed(self):
        source = 'store.get("trakt.access_token"); headers["Authorization"] = "Bearer $token"\n' + '"trakt.client_id" to "synthetic-client-identifier-for-tests"'
        self.assertEqual([], scan_text(source, 'example.kt', set()))

    def test_private_address_is_detected_without_exposing_the_value(self):
        address = '.'.join(map(str, [192, 168, 17, 42]))
        issues = scan_text('connect ' + address, 'example.txt', set())
        self.assertTrue(any(i['rule'] == 'private-network-address' for i in issues))
        self.assertNotIn(address, json.dumps(issues))

    def test_random_client_id_and_hex_account_key_need_review(self):
        for value in [''.join(['aB7cD9eF2gH4jK6m', 'N8pQ0rS3tU5vW1xY']) * 2, hashlib.sha256(b'not-a-real-account').hexdigest()]:
            issues = scan_text('"trakt.client_id" to "' + value + '"', 'example.kt', set())
            self.assertTrue(issues)
            self.assertNotIn(value, json.dumps(issues))

    def test_runtime_preferences_and_signing_keys_are_rejected(self):
        for name in ['shared_prefs/pov_accounts_encrypted.xml', 'local.properties', 'private.keystore', 'verification/screen.png']:
            self.assertTrue(entry_issues(name, 'source'))

    def test_apk_signature_public_certificate_is_not_a_private_key(self):
        self.assertEqual([], entry_issues('META-INF/CERT.RSA', 'apk'))

    def test_literal_bearer_and_query_secrets_are_detected(self):
        for value in ['Bearer ' + 'opaque-' * 8, 'https://example.invalid/file?token=' + 'opaque-' * 8]:
            self.assertTrue(scan_text(value, 'example.txt', set()))

    def test_public_download_digest_has_a_narrow_explicit_exemption(self):
        digest = hashlib.sha256(b'public-download-integrity').hexdigest()
        self.assertEqual([], scan_text('"' + digest + '"', 'bootstrap.ps1', {digest}))
        self.assertTrue(scan_text('"' + digest + '"', 'unexpected.kt', set()))

    def test_public_source_revision_is_allowed_only_in_its_reviewed_upstream_url(self):
        source = PUBLIC_SOURCE_URL_ROOTS[0]
        revision = source.rsplit('/', 1)[-1]
        self.assertEqual([], scan_text(source + '/libvlc/patches', 'NOTICE.txt', set()))
        self.assertTrue(scan_text('"' + revision + '"', 'unexpected.kt', set()))
        self.assertTrue(scan_text('https://example.invalid/config/' + revision, 'unexpected.md', set()))
        self.assertTrue(scan_text(source + '?token=' + revision, 'unexpected.md', set()))
        # A second, unrelated opaque path component must still be reviewed.
        value = hashlib.sha256(b'not-an-upstream-revision').hexdigest()
        self.assertTrue(scan_text(source + '/' + value, 'unexpected.md', set()))

    def test_public_source_archive_requires_the_exact_reviewed_official_url(self):
        unrelated = hashlib.sha256(b'not-an-archive-credential').hexdigest()
        for source in PUBLIC_SOURCE_ARCHIVE_URLS:
            revision = source.rsplit('/', 2)[-2]
            self.assertEqual([], scan_text(source, 'docs/LIBVLC.md', set()))
            self.assertTrue(scan_text('"' + revision + '"', 'unexpected.kt', set()))
            self.assertTrue(scan_text(source.replace('code.videolan.org', 'example.invalid'), 'unexpected.md', set()))
            self.assertTrue(scan_text(source.replace('https://', 'http://'), 'unexpected.md', set()))
            self.assertTrue(scan_text(source.rsplit('/', 1)[0] + '/unreviewed.tar.gz', 'unexpected.md', set()))
            self.assertTrue(scan_text(source.replace(revision, unrelated[:40]), 'unexpected.md', set()))
            self.assertTrue(scan_text(source + '?token=' + unrelated, 'unexpected.md', set()))
            self.assertTrue(scan_text(source + '#token=' + unrelated, 'unexpected.md', set()))
            self.assertTrue(scan_text(source + '/' + unrelated, 'unexpected.md', set()))

    def test_upstream_entry_classification_requires_exact_bytes_and_path(self):
        name = 'lib/test/libfixture.so'
        fixture = ('public parser marker ' + '-----BEGIN ' + 'PRIVATE KEY-----').encode()
        digest = hashlib.sha256(fixture).hexdigest()

        def archive(entry, content):
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, 'w') as output:
                output.writestr(entry, content)
            stream.seek(0)
            return stream

        with mock.patch.dict(REVIEWED_UPSTREAM_APK, {name: digest}):
            exact = audit_archive(archive(name, fixture), 'apk')
            self.assertEqual(1, len(exact['reviewed_upstream_entries']))
            altered = audit_archive(archive(name, fixture + b' altered'), 'apk')
            self.assertEqual([], altered['reviewed_upstream_entries'])
            self.assertTrue(any(i['rule'] == 'reviewed-upstream-bytes-changed' for i in altered['issues']))
            self.assertTrue(any(i['rule'] == 'private-key-material' for i in altered['issues']))
            other_path = audit_archive(archive('assets/unreviewed.bin', fixture), 'apk')
            self.assertEqual([], other_path['reviewed_upstream_entries'])
            self.assertTrue(any(i['rule'] == 'private-key-material' for i in other_path['issues']))

    def test_binary_utf16_private_configuration_is_scanned(self):
        contents = io.BytesIO()
        with zipfile.ZipFile(contents, 'w') as archive:
            archive.writestr('app/src/main/assets/unexpected.xml', ('.'.join(map(str, [10, 7, 8, 9]))).encode('utf-16le'))
        contents.seek(0)
        self.assertTrue(any(i['rule'] == 'private-network-address' for i in audit_archive(contents, 'source')['issues']))

    def test_source_xml_literal_configuration_is_detected(self):
        value = ''.join(['aB7cD9eF2gH4jK6m', 'N8pQ0rS3tU5vW1xY']) * 2
        text = '<string name="trakt_client_id">' + value + '</string>'
        self.assertTrue(any(i['rule'] == 'embedded-account-configuration' for i in scan_text(text, 'strings.xml', set())))

    def test_binary_resource_string_is_detected_without_quote_context(self):
        value = ''.join(['aB7cD9eF2gH4jK6m', 'N8pQ0rS3tU5vW1xY']) * 2
        self.assertTrue(scan_text(value, 'resources.arsc', set(), binary=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--report', type=Path, help='Optional JSON report path; contains no matched values.')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        return 0 if unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(SyntheticTests)).wasSuccessful() else 1
    if not args.apk or not args.source:
        parser.error('--apk and --source are both required unless --self-test is selected')
    if not args.apk.is_file() or not args.source.is_file():
        parser.error('Both artifact files must exist')
    try:
        hashes = public_hashes(args.source)
    except (zipfile.BadZipFile, OSError):
        hashes = set()
    archives = [audit_archive(args.apk, 'apk', hashes), audit_archive(args.source, 'source', hashes)]
    report = {'passed': all(not item['issues'] for item in archives), 'network_used': False, 'archives': archives,
              'limitations': ['Heuristic static checks cannot prove every secret is absent.',
                              'No OCR or visual review of images/video; no encrypted payload inspection.',
                              'Does not prove APK/source equivalence or third-party service behavior.',
                              'Exact reviewed LibVLC AAR entries are classified by APK path and SHA256; changed bytes remain failures and are scanned.',
                              'Reviewed synthetic network tests classify only IP-address findings at exact source paths and LF-normalized SHA256; all secret checks remain active.',
                              'Recognized public synthetic fixtures, explicit bootstrap SHA256 pins and exact reviewed upstream source-revision URLs are allowed.']}
    output = json.dumps(report, indent=2)
    print(output)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output + '\n', encoding='utf-8')
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
