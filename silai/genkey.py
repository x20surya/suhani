"""Generate the shared app key that guards the API when Suhani is on the web.

Run:  python -m silai.genkey
Then paste the APP_KEY line into .env and restart the web server.
"""

import secrets


def main() -> None:
    key = secrets.token_urlsafe(32)
    print("Paste this into .env:\n")
    print(f"APP_KEY={key}")
    print("\nThen open the app once on her phone with:\n")
    print(f"    https://<your-host>/?k={key}\n")
    print("The PWA stores it and sends it on every request from then on,")
    print("so she never sees a login. Treat the link like a password.")


if __name__ == "__main__":
    main()
