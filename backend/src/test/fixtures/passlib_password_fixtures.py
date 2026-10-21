"""Offline, synthetic-only T0.2 fixtures; never imports the app or reads .env/DB.

Generate: python src/test/fixtures/passlib_password_fixtures.py generate
Cross-check Java outputs: ... verify-java
Requires the existing local Passlib 1.7.4 / bcrypt 4.0.1 test environment.
Fixed salts are ONLY for repeatable fixtures, never for application passwords.
"""

import hashlib
import json
from pathlib import Path
import sys

import bcrypt as bcrypt_backend
import passlib
from passlib.context import CryptContext
from passlib.utils.binary import bcrypt64


BACKEND = Path(__file__).resolve().parents[3]
FIXTURE = BACKEND / "src/test/resources/password/passlib-bcrypt-vectors.json"
JAVA_OUTPUT = BACKEND / "target/verification/password-java-synthetic.json"
CONTEXT = CryptContext(schemes=["bcrypt"], deprecated="auto", bcrypt__truncate_error=True)


def versions():
    assert passlib.__version__ == "1.7.4", "Use the recorded fixture generator version."
    assert bcrypt_backend.__version__ == "4.0.1", "Use the recorded bcrypt backend."
    return {"passlib": passlib.__version__, "bcrypt": bcrypt_backend.__version__}


def generate():
    vectors = [
        ("ascii", "SyntheticPass123!"),
        ("whitespace", "  Synthetic Pass  "),
        ("vietnamese", "Mật-khẩu-thử-nghiệm!"),
        ("emoji", "EZam-🔐-🧪-2026"),
        ("high-bit-utf8", "ÿÿÿÿÿÿÿÿ"),
        ("unicode-nfc", "éééééééé"),
        ("unicode-nfd", "e\u0301" * 8),
        ("ascii-71-bytes", "a" * 71),
        ("ascii-72-bytes", "a" * 72),
        ("utf8-72-bytes", "é" * 36),
        ("emoji-72-bytes", "🔐" * 18),
        ("mixed-72-bytes", "a" * 21 + "ế" * 17),
    ]
    result = {"syntheticOnly": True, "generator": versions(),
              "profile": {"revision": "2b", "cost": 12, "encoding": "UTF-8",
                          "maxBytes": 72, "nulAllowed": False}, "vectors": []}
    for name, password in vectors:
        salt = bcrypt64.encode_bytes(hashlib.sha256(name.encode("ascii")).digest()[:16]).decode("ascii")
        hashed = CONTEXT.hash(password, rounds=12, ident="2b", salt=salt)
        wrong = "X" + password[1:]
        assert hashed.startswith("$2b$12$") and CONTEXT.verify(password, hashed)
        assert not CONTEXT.verify(wrong, hashed)
        result["vectors"].append({"id": name, "password": password, "wrongPassword": wrong,
                                  "utf8Bytes": len(password.encode("utf-8")), "hash": hashed})
    FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: generated {len(vectors)} synthetic Passlib cost-12 vectors; no app/DB access.")


def verify_java():
    versions()
    source = json.loads(FIXTURE.read_text(encoding="utf-8"))
    output = json.loads(JAVA_OUTPUT.read_text(encoding="utf-8"))
    assert source["syntheticOnly"] is True and output["syntheticOnly"] is True
    expected = {item["id"]: item for item in source["vectors"]}
    assert len(output["vectors"]) == len(expected)
    assert {item["id"] for item in output["vectors"]} == set(expected)
    for item in output["vectors"]:
        vector = expected[item["id"]]
        assert item["hash"].startswith("$2a$12$"), "Java must generate native cost-12 hashes."
        assert CONTEXT.verify(vector["password"], item["hash"]), "Passlib must verify Java hash."
        assert not CONTEXT.verify(vector["wrongPassword"], item["hash"]), "Wrong password must fail."
    result = {"status": "PASS", "generator": versions(), "vectors": len(expected),
              "correctAssertions": len(expected), "wrongAssertions": len(expected),
              "fixtureSha256": hashlib.sha256(FIXTURE.read_bytes()).hexdigest(),
              "javaOutputSha256": hashlib.sha256(JAVA_OUTPUT.read_bytes()).hexdigest(),
              "databaseAccess": False, "productionCredentialsUsed": False}
    (JAVA_OUTPUT.parent / "password-crosscheck.json").write_text(
        json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: Passlib independently verified {len(expected)} Java hashes (correct + wrong); no DB access.")


if __name__ == "__main__":
    if sys.argv[1:] == ["generate"]:
        generate()
    elif sys.argv[1:] == ["verify-java"]:
        verify_java()
    else:
        raise SystemExit("Usage: passlib_password_fixtures.py generate|verify-java")
