#!/usr/bin/env python3
"""Record local hashes for every object recovered before transfer cleanup.

This proves preservation of the uploaded inventory, not benchmark acceptance or
recovery of measurements that never reached S3.
"""

import hashlib
import json
from pathlib import Path
import sys


def verify(root, prefix):
    root = Path(root)
    inventory = json.loads((root / "inventory.json").read_text())
    if inventory.get("IsTruncated"):
        raise ValueError("incomplete result inventory")
    objects = (root / "objects").resolve()
    receipts = []
    seen = set()
    for item in inventory.get("Contents", []):
        key = item["Key"]
        if not key.startswith(prefix):
            raise ValueError("result key outside campaign prefix")
        relative = key[len(prefix):]
        path = (objects / relative).resolve()
        if not relative or not path.is_relative_to(objects) or path in seen:
            raise ValueError("invalid or duplicate result path")
        seen.add(path)
        if path.stat().st_size != item["Size"]:
            raise ValueError("recovered result size mismatch: " + key)
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        receipts.append({"key": key, "size": item["Size"], "sha256": digest})
    receipt = {"schema_version": 1, "status": "verified", "objects": receipts,
               "scope": "uploaded objects only; unuploaded host data may be unavailable"}
    (root / "verified.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


if __name__ == "__main__":
    verify(sys.argv[1], sys.argv[2])
