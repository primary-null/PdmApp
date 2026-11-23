"""Check a publication tree or all reachable Git history for common privacy leaks.

This is a review aid, not a proof of anonymity. Exact private identifiers belong
in an external JSON string array supplied with --private-patterns, never in Git.
"""
from __future__ import annotations

import argparse
import ipaddress
import json
from pathlib import Path
import re
import subprocess


AUTHOR = "primary-null <285952344+primary-null@users.noreply.github.com>"
AUTHOR_EMAIL = "285952344+primary-null@users.noreply.github.com"
USER_PATH = re.compile(
    r"(?:[a-z]:[\\/](?:users|documents and settings)[\\/][^\\/\s]+"
    r"|(?<![\w/])/(?:users|home)/[^/\s]+)", re.I,
)
EMAIL = re.compile(r"\b(?!this@)[\w.!#$%&'*+/=?^`{|}~-]+@[\w.-]+\.[a-z]{2,}\b", re.I)
IPV4 = re.compile(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])")
MAC = re.compile(r"(?<![\w:])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![\w:])", re.I)
LOCAL_HOST = re.compile(r'''["'](?:[\w-]+\.)+(?:local|lan|lab|internal)(?=[:/"'])''', re.I)
EXAMPLE_NETS = tuple(ipaddress.ip_network(n) for n in (
    "192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", "127.0.0.0/8",
))
VENDOR_PREFIXES = ("app/src/main/assets/xterm/", "app/src/main/assets/licenses/")
EXCLUDED_PREFIXES = (".agents/", ".screens/", ".logs/", ".publication/", ".local/", "app/build/", ".gradle/")
EXCLUDED_NAMES = {"local.properties", "key.properties", "keystore.properties", ".env"}


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args])


def check(data: bytes, label: str, patterns: list[bytes]) -> set[str]:
    findings = set()
    lower = data.lower()
    if any(pattern in lower for pattern in patterns):
        findings.add("private identifier")
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        offset = 8
        while offset + 12 <= len(data):
            size = int.from_bytes(data[offset:offset + 4], "big")
            kind = data[offset + 4:offset + 8]
            if kind in {b"tEXt", b"zTXt", b"iTXt", b"eXIf"}:
                findings.add("PNG text or EXIF metadata requires review")
            offset += size + 12
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return findings
    if USER_PATH.search(text):
        findings.add("personal filesystem path")
    if LOCAL_HOST.search(text):
        findings.add("non-example local hostname")
    if any(not value.lower().startswith(("02:00:00:00:00:", "00:00:5e:00:53:")) for value in MAC.findall(text)):
        findings.add("non-example MAC address")
    if not label.startswith(VENDOR_PREFIXES) and label != "LICENSE":
        for email in EMAIL.findall(text):
            domain = email.rsplit("@", 1)[1].lower()
            if not (email.lower() == AUTHOR_EMAIL or domain.endswith((".invalid", ".example")) or domain in {"example.com", "example.org", "example.net"}):
                findings.add("non-example email address")
    for value in IPV4.findall(text):
        try:
            address = ipaddress.ip_address(value)
        except ValueError:
            continue
        # Unspecified address and the CGNAT routing-range base are protocol constants.
        if value not in {"0.0.0.0", "100.64.0.0"} and not any(address in net for net in EXAMPLE_NETS):
            findings.add("non-example IPv4 address")
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", action="store_true", help="Inspect blobs, paths and commit metadata reachable from every local ref")
    parser.add_argument("--private-patterns", type=Path, help="External JSON array of exact private strings")
    args = parser.parse_args()
    root = Path(git("rev-parse", "--show-toplevel").decode().strip()).resolve()
    patterns = []
    if args.private_patterns:
        source = args.private_patterns.resolve()
        if source == root or root in source.parents:
            parser.error("Private patterns must be stored outside the repository")
        values = json.loads(source.read_text(encoding="utf-8"))
        if not isinstance(values, list) or not all(isinstance(v, str) and len(v) >= 4 for v in values):
            parser.error("Private patterns must be a JSON array of strings of at least four characters")
        patterns = [v.encode("utf-8").lower() for v in values]
    failures = []
    if args.history:
        entries = []
        for line in git("rev-list", "--objects", "--all").decode().splitlines():
            oid, _, name = line.partition(" ")
            if git("cat-file", "-t", oid).strip() == b"blob":
                entries.append((name, git("cat-file", "blob", oid)))
        for oid in git("rev-list", "--all").decode().splitlines():
            metadata = git("show", "-s", "--format=%an <%ae>%n%cn <%ce>%n%B", oid)
            lines = metadata.decode().splitlines()
            if lines[:2] != [AUTHOR, AUTHOR]:
                failures.append(("commit " + oid[:12], {"unexpected author or committer"}))
            result = check(metadata, "commit metadata", patterns)
            if result:
                failures.append(("commit " + oid[:12], result))
    else:
        names = set(git("ls-files", "--cached", "--others", "--exclude-standard", "-z").decode().split("\0"))
        entries = [(name, (root / name).read_bytes()) for name in sorted(names) if name and (root / name).is_file()]
    for name, data in entries:
        result = check(data, name, patterns) | check(name.encode(), "path", patterns)
        path = Path(name)
        if (name.startswith(EXCLUDED_PREFIXES) or path.name in EXCLUDED_NAMES
                or path.name.startswith(".env.")
                or path.suffix.lower() in {".jks", ".keystore", ".pem", ".p12", ".pfx", ".apk", ".aab", ".log"}):
            result.add("private or generated file")
        if result:
            failures.append((name, result))
    for label, reasons in failures:
        print(f"FAIL {label}: {', '.join(sorted(reasons))}")
    print(f"Checked {len(entries)} file objects; {len(failures)} findings. Review images, metadata and remote surfaces separately.")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
