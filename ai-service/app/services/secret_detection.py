import threading

from detect_secrets.core.scan import scan_line
from detect_secrets.settings import default_settings, get_settings

_DETECT_SECRETS_LOCK = threading.Lock()


class SecretDetectionFailure(Exception):
    """Raised when secret detection could not complete reliably."""


def contains_potential_secret(value: str) -> bool:
    """Scan untrusted text without exposing detected values to application code."""
    try:
        with _DETECT_SECRETS_LOCK, default_settings():
            # Entropy-only plugins flag ordinary prose far too often for this input.
            get_settings().disable_plugins(
                "Base64HighEntropyString",
                "HexHighEntropyString",
            )
            return any(
                secret
                for line in value.splitlines() or [value]
                for secret in scan_line(line)
            )
    except Exception as exc:
        raise SecretDetectionFailure from exc
