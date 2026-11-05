import soulfire


def test_public_exports_keep_the_existing_sdk_name() -> None:
    assert "SoulFire" in soulfire.__all__
    assert "Soulfire" not in soulfire.__all__
    for name in soulfire.__all__:
        assert hasattr(soulfire, name)
