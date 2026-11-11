#!/usr/bin/env python3
"""Send one already signed, pinned Asterion Core APK to the paired phone.

Uses the existing authorized Asterion Control worker. Does not install, remove,
re-sign, re-pair, or modify app data. The Android package installer remains the
authority for confirmation and in-place upgrade.
"""
from __future__ import annotations

import hashlib
import ipaddress
import json
import os
from pathlib import Path
import subprocess
import time
from urllib.parse import urlparse

import requests

APK = Path(r"D:\AsterionCoreInstaller\AsterionCore-v2.0.36-Alternate-SIGNED.apk")
EXPECTED_SHA256 = "065ca411b7a56ec21765a866fa1686bc3f939c787534605abb18fc77d004576f"
EXPECTED_CERT = "4d652e25dfef5d90a3494a993c0e3d60229790d07017297d540c87621508a94e"


def verified_source() -> tuple[Path, str]:
    if not APK.is_file():
        raise FileNotFoundError(f"Missing signed Core package: {APK}")
    if not 400_000_000 <= APK.stat().st_size <= 500_000_000:
        raise RuntimeError("Core package size outside approved v2.0.36 bounds")
    digest = hashlib.sha256()
    with APK.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(block)
    sha = digest.hexdigest()
    if sha != EXPECTED_SHA256:
        raise RuntimeError(f"Core package SHA-256 changed: {sha}")
    apksigner = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/build-tools/35.0.1/apksigner.bat"
    verified = subprocess.run([str(apksigner), "verify", "--print-certs", str(APK)],
                              capture_output=True, text=True, check=True)
    observed = next((line.split(":", 1)[1].strip().replace(":", "").lower()
                     for line in verified.stdout.splitlines()
                     if line.startswith("Signer #1 certificate SHA-256 digest:")), "")
    if observed != EXPECTED_CERT:
        raise RuntimeError("APK is not signed with the permanent Core signing certificate")
    return APK, sha


def authenticated_control() -> tuple[str, str]:
    folder = Path(os.environ["LOCALAPPDATA"]) / "AsterionControl"
    url = (folder / "agent-host.txt").read_text(encoding="utf-8").strip().rstrip("/")
    parsed = urlparse(url)
    try:
        address = ipaddress.ip_address(parsed.hostname or "")
    except ValueError as exc:
        raise RuntimeError("Asterion Control must use an IP endpoint assigned by the owner") from exc
    if parsed.scheme != "http" or parsed.port != 8911 or not (address.is_private or address.is_loopback):
        raise RuntimeError("Refusing nonlocal or unexpected Control transfer endpoint")
    token = (folder / "control-token.txt").read_text(encoding="utf-8").strip()
    if len(token) < 30:
        raise RuntimeError("Asterion Control has no valid local authorization token")
    return url, token


def main() -> None:
    apk, sha = verified_source()
    url, token = authenticated_control()
    with requests.Session() as session:
        session.trust_env = False
        session.headers.update({"Authorization": "Bearer " + token})
        reply = session.post(url + "/api/v1/phone/transfers",
                             json={"pc_path": str(apk), "name": apk.name},
                             timeout=(10, 45))
        reply.raise_for_status()
        transfer_id = reply.json().get("id")
        if not isinstance(transfer_id, str) or not transfer_id:
            raise RuntimeError("Control did not acknowledge transfer")
        print(json.dumps({"event": "submitted", "id": transfer_id,
                          "size": apk.stat().st_size, "sha256": sha}), flush=True)
        deadline = time.monotonic() + 1200
        while time.monotonic() < deadline:
            response = session.get(url + "/api/v1/phone/transfers/" + transfer_id,
                                   timeout=(10, 35))
            response.raise_for_status()
            status = response.json()
            if status.get("state") in ("completed", "failed", "cancelled"):
                break
            time.sleep(2)
        else:
            raise TimeoutError("Transfer not acknowledged complete by Control")
        receipt = status.get("result") or {}
        verified = (status.get("state") == "completed"
                    and receipt.get("verified") is True
                    and receipt.get("transport") == "private-wifi"
                    and receipt.get("size") == apk.stat().st_size
                    and str(receipt.get("sha256", "")).lower() == sha)
        print(json.dumps({"event": "verified_receipt", "id": transfer_id,
                          "verified": verified, "state": status.get("state"),
                          "android_location": receipt.get("location", ""),
                          "bytes_sent": status.get("bytes_sent"),
                          "error": status.get("error")}), flush=True)
        if not verified:
            raise RuntimeError("Android did not confirm a byte-identical verified transfer")


if __name__ == "__main__":
    main()
