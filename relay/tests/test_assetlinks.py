"""
Ссылка Point `/d/…` открывается в Point, а не в браузере (#1083).

Android отдаёт https-ссылку приложению, только если `/.well-known/assetlinks.json` называет
отпечаток ключа, которым оно подписано. Ключ релиза сменился 2026-11-26 (прежний потерян):
новые сборки должны открывать ссылки, и старые, которые ещё стоят у людей, — тоже.
"""

NEW_RELEASE = "5A:51:00:AD:C6:34:2C:D3:4F:90:57:61:A7:A6:CC:47:63:9A:5C:64:53:DE:92:23:B7:75:42:42:4E:1A:6B:09"
LOST_RELEASE = "A9:55:88:14:C0:C1:BC:30:FB:BD:CE:7C:5E:7C:55:50:A7:AB:3D:1D:C4:BE:9A:7F:53:A0:D0:DC:6A:5C:E3:19"
DEBUG_STAND = "F3:D9:DF:F6:D8:96:66:BE:F8:79:37:A4:A9:AF:3F:AF:B2:C7:F6:1C:D3:0D:BE:87:8A:FA:7C:27:90:08:20:F3"


def _point_target(point):
    got = point.client.get("/.well-known/assetlinks.json")
    assert got.status_code == 200
    assert got.headers["content-type"].startswith("application/json"), "Android читает только JSON"
    targets = [s["target"] for s in got.json() if s["target"].get("package_name") == "com.point"]
    assert len(targets) == 1, "Point назван ровно одной записью"
    return got.json()[0], targets[0]


def test_ссылку_открывают_сборки_нового_ключа_старого_и_стенда(point):
    statement, target = _point_target(point)

    prints = target["sha256_cert_fingerprints"]
    assert NEW_RELEASE in prints, "сборки нового ключа отдали бы ссылку браузеру"
    assert LOST_RELEASE in prints, "у кого стоит прежняя сборка, ссылка ушла бы в браузер"
    assert DEBUG_STAND in prints, "стенд перестал бы открывать ссылки"
    assert statement["relation"] == ["delegate_permission/common.handle_all_urls"]


def test_отпечаток_в_виде_который_понимает_android(point):
    """32 байта заглавным шестнадцатеричным через двоеточие: другой вид Android молча отвергает."""
    _, target = _point_target(point)

    for fp in target["sha256_cert_fingerprints"]:
        parts = fp.split(":")
        assert len(parts) == 32, fp
        assert all(len(p) == 2 and p == p.upper() and int(p, 16) >= 0 for p in parts), fp
