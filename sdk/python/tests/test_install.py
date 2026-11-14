import pytest

from soulfire._install import _require_sha256_digest, _resolve_dedicated_asset


def test_resolve_dedicated_asset_prefers_the_versioned_jar() -> None:
    digest = f"sha256:{'a' * 64}"
    release = {
        "tag_name": "2.9.1",
        "assets": [
            {
                "name": "SoulFireCLI-2.9.1.jar",
                "browser_download_url": "https://example.com/cli.jar",
                "digest": digest,
            },
            {
                "name": "SoulFireDedicated-2.9.1.jar",
                "browser_download_url": "https://example.com/dedicated.jar",
                "digest": digest,
            },
        ],
    }

    asset = _resolve_dedicated_asset(release, None)

    assert asset["name"] == "SoulFireDedicated-2.9.1.jar"
    assert asset["browser_download_url"] == "https://example.com/dedicated.jar"


def test_release_digest_must_be_sha256() -> None:
    with pytest.raises(RuntimeError, match="SHA-256"):
        _require_sha256_digest("sha512:not-supported", "Soulfire release")
