"""Check a Git archive against the frozen candidate receipt and extracted worker source."""

import csv
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import re
import tarfile


def git_object(kind, payload):
    return hashlib.sha1(kind.encode() + b" " + str(len(payload)).encode() + b"\0" + payload).digest()


def git_tree(files):
    tree = {}
    for path, (mode, payload) in files.items():
        directory = tree
        for name in PurePosixPath(path).parts[:-1]:
            directory = directory.setdefault(name, {})
        name = PurePosixPath(path).name
        if name in directory:
            raise ValueError("duplicate archive entry")
        directory[name] = (mode, git_object("blob", payload))

    def encode(directory):
        payload = b""
        for name in sorted(directory, key=lambda key: (key + ("/" if isinstance(directory[key], dict) else "")).encode()):
            value = directory[name]
            mode, identity = ("40000", encode(value)) if isinstance(value, dict) else value
            payload += mode.encode() + b" " + name.encode() + b"\0" + identity
        return git_object("tree", payload)

    return encode(tree).hex()


def read(root, archive, provenance):
    """Return checked source identity and uncompressed tar bytes. No synthetic Git commits."""
    root = root.resolve()
    if (root / ".git").exists():
        raise ValueError("archive mode requires an extracted source directory, not a Git checkout")
    with provenance.open() as file:
        rows = list(csv.DictReader(file, delimiter="\t"))
    if len(rows) != 1:
        raise ValueError("candidate provenance must contain exactly one row")
    receipt = rows[0]
    for field in ("candidate_commit", "root_tree"):
        if not re.fullmatch(r"[0-9a-f]{40}", receipt[field]):
            raise ValueError("invalid frozen source identity")
    archive_bytes = archive.read_bytes()
    if hashlib.sha256(archive_bytes).hexdigest() != receipt["archive_sha256"]:
        raise ValueError("source archive checksum mismatch")
    files = {}
    with tarfile.open(fileobj=io.BytesIO(archive_bytes), mode="r:*") as source:
        if source.pax_headers.get("comment") != receipt["candidate_commit"]:
            raise ValueError("Git archive identifies a different candidate commit")
        for member in source:
            name = PurePosixPath(member.name)
            if not name.parts or name.is_absolute() or ".." in name.parts or name.parts[0] in {".git", "target"}:
                raise ValueError("unsafe source archive path")
            if member.isdir():
                continue
            if str(name) in files:
                raise ValueError("duplicate source archive path")
            if member.issym():
                mode, payload = "120000", member.linkname.encode()
            elif member.isfile():
                mode = "100755" if member.mode & 0o111 else "100644"
                payload = source.extractfile(member).read()
            else:
                raise ValueError("unsupported source archive entry")
            files[str(name)] = (mode, payload)
        # Reuse the original tar bytes for the isolated build. TarFile has already
        # checked compression and every member; extraction still uses the data filter.
        source.fileobj.seek(0)
        uncompressed = source.fileobj.read()
    if git_tree(files) != receipt["root_tree"]:
        raise ValueError("archive content does not match the candidate Git tree")
    for name, (mode, payload) in files.items():
        path = root / name
        if not path.parent.resolve().is_relative_to(root):
            raise ValueError("source path traverses a directory symlink")
        if mode == "120000":
            if not path.is_symlink() or str(path.readlink()).encode() != payload:
                raise ValueError("extracted source symlink differs from archive")
        elif (path.is_symlink() or not path.is_file() or path.read_bytes() != payload or
              bool(path.stat().st_mode & 0o111) != (mode == "100755")):
            raise ValueError("extracted source differs from archive")
    expected_directories = {str(parent) for name in files for parent in PurePosixPath(name).parents}
    for directory, directories, names in os.walk(root, followlinks=False):
        # Only Maven/collector build output may be added to the extracted source.
        if Path(directory) == root and "target" in directories:
            directories.remove("target")
        for name in directories + names:
            path = Path(directory) / name
            relative = str(path.relative_to(root))
            if relative not in files and not (path.is_dir() and relative in expected_directories):
                raise ValueError(f"unrecorded file in worker source: {relative}")
    return {"source_commit": receipt["candidate_commit"], "source_tree": receipt["root_tree"]}, uncompressed
