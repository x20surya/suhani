"""Generate VAPID keys for web push. Run once:  python -m silai.genvapid
Prints the three .env lines to paste (no signup, no network)."""

import base64

from py_vapid import Vapid
from cryptography.hazmat.primitives import serialization


def main() -> None:
    v = Vapid()
    v.generate_keys()
    private_pem = v.private_pem().decode() if isinstance(v.private_pem(), bytes) else v.private_pem()
    # Application-server key for the browser: uncompressed point, base64url.
    pub_raw = v.public_key.public_bytes(
        serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint
    )
    pub_b64 = base64.urlsafe_b64encode(pub_raw).rstrip(b"=").decode()
    priv_raw = v.private_key.private_numbers().private_value.to_bytes(32, "big")
    priv_b64 = base64.urlsafe_b64encode(priv_raw).rstrip(b"=").decode()

    print("# Paste these into .env:")
    print(f"VAPID_PUBLIC_KEY={pub_b64}")
    print(f"VAPID_PRIVATE_KEY={priv_b64}")
    print("VAPID_SUBJECT=mailto:suryanshu659@gmail.com")
    _ = private_pem  # PEM form available if ever needed


if __name__ == "__main__":
    main()
