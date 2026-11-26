"""
Ссылка Point `/d/…` открывается в Point, а не в браузере (#1083).

Android отдаёт https-ссылку приложению, только если `/.well-known/assetlinks.json` называет его
пакет и отпечаток ключа, которым оно подписано. 2026-11-26 сменились и ключ релиза (прежний
потерян), и пакет (`com.point` в Google Play занят чужим, Point там — `app.leerio.point`):
новые сборки должны открывать ссылки, и старые, которые ещё стоят у людей, — тоже.
"""

NEW_RELEASE = "5A:51:00:AD:C6:34:2C:D3:4F:90:57:61:A7:A6:CC:47:63:9A:5C:64:53:DE:92:23:B7:75:42:42:4E:1A:6B:09"
LOST_RELEASE = "A9:55:88:14:C0:C1:BC:30:FB:BD:CE:7C:5E:7C:55:50:A7:AB:3D:1D:C4:BE:9A:7F:53:A0:D0:DC:6A:5C:E3:19"
DEBUG_STAND = "F3:D9:DF:F6:D8:96:66:BE:F8:79:37:A4:A9:AF:3F:AF:B2:C7:F6:1C:D3:0D:BE:87:8A:FA:7C:27:90:08:20:F3"


def _statements(point):
    got = point.client.get("/.well-known/assetlinks.json")
    assert got.status_code == 200
    assert got.headers["content-type"].startswith("application/json"), "Android читает только JSON"
    return got.json()


def _prints(point, package):
    targets = [s["target"] for s in _statements(point) if s["target"].get("package_name") == package]
    assert len(targets) == 1, f"{package} назван ровно одной записью"
    return targets[0]["sha256_cert_fingerprints"]


def test_ссылку_открывает_point_из_google_play_и_стенд(point):
    prints = _prints(point, "app.leerio.point")

    assert NEW_RELEASE in prints, "Point из Play и с сайта отдал бы ссылку браузеру"
    assert DEBUG_STAND in prints, "стенд перестал бы открывать ссылки"


def test_прежние_сборки_тоже_открывают_ссылку(point):
    prints = _prints(point, "com.point")

    assert LOST_RELEASE in prints, "у кого стоит прежняя сборка, ссылка ушла бы в браузер"
    assert DEBUG_STAND in prints


def test_каждая_запись_в_виде_который_понимает_android(point):
    """32 байта заглавным шестнадцатеричным через двоеточие: другой вид Android молча отвергает."""
    for statement in _statements(point):
        assert statement["relation"] == ["delegate_permission/common.handle_all_urls"]
        assert statement["target"]["namespace"] == "android_app"
        for fp in statement["target"]["sha256_cert_fingerprints"]:
            parts = fp.split(":")
            assert len(parts) == 32, fp
            assert all(len(p) == 2 and p == p.upper() and int(p, 16) >= 0 for p in parts), fp
