#!/usr/bin/env python3
"""Set GitHub repo secrets (encrypted with repo public key)."""
import base64, json, sys, urllib.request
from nacl.public import SealedBox, PublicKey

TOKEN = sys.argv[1]
REPO = sys.argv[2]
SECRETS = {
    "RELEASE_KEYSTORE_BASE64": open("/home/z/my-project/AimbotPro/AimbotProV3/keystore/release.jks", "rb").read(),
    "KEYSTORE_PASSWORD": b"aimbotpro2024",
    "KEY_ALIAS": b"aimbotpro",
    "KEY_PASSWORD": b"aimbotpro2024",
}

# 1) Get repo public key
req = urllib.request.Request(
    f"https://api.github.com/repos/{REPO}/actions/secrets/public-key",
    headers={"Authorization": f"token {TOKEN}", "Accept": "application/vnd.github+json"},
)
with urllib.request.urlopen(req) as r:
    pk_data = json.loads(r.read())
pub_key = PublicKey(base64.b64decode(pk_data["key"]))
key_id = pk_data["key_id"]
print(f"Public key id: {key_id}")

box = SealedBox(pub_key)

for name, value in SECRETS.items():
    if isinstance(value, bytes) and name == "RELEASE_KEYSTORE_BASE64":
        value = base64.b64encode(value).decode()  # double-encode: b64 bytes → b64 string bytes
        # Actually: we want to store the base64 string of the keystore
        # The value should be a string, which GitHub will decrypt
        encrypted = box.encrypt(value.encode())
    else:
        encrypted = box.encrypt(value if isinstance(value, bytes) else value.encode())
    
    payload = json.dumps({"encrypted_value": base64.b64encode(encrypted).decode(), "key_id": key_id}).encode()
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/actions/secrets/{name}",
        data=payload,
        method="PUT",
        headers={"Authorization": f"token {TOKEN}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as r:
        print(f"  ✅ {name} set ({len(value)} bytes)")

print("\nAll secrets configured.")
