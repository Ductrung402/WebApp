import importlib.util
import json
import hashlib
import importlib.metadata
from pathlib import Path

root = Path(__file__).resolve().parents[4]
source = root / '.tools/railway-publish/backend/app/schemas/auth.py'
spec = importlib.util.spec_from_file_location('legacy_auth_schema_fixture', source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
emails = [
    'Teacher@Example.COM', ' user@example.com ', 'Teacher <Teacher@Example.COM>',
    'test+tag@gmail.com', 'a.b@example.org', 'a..b@example.com', '.a@example.com', 'a.@example.com',
    'no-at', 'a@localhost', 'a@b', 'a@example.test', 'a@example.invalid', 'a@example.local',
    'a@123.123', 'a@127.0.0.1', 'a@[127.0.0.1]', 'a@bad_domain.com', 'a@-bad.com',
    'a@example.com.', 'a@.example.com', 'a@@example.com', 'a b@example.com', '"a b"@example.com',
    'a\u00a0b@example.com', 'nguy\u1ec5n@example.com', 'a@b\u00fccher.de',
    'a@xn--bcher-kva.de', 'a\u200bb@example.com', 'a@example.onion',
    'a@x.example', 'a' * 65 + '@example.com', '', '\tA@example.com\n',
]
cases = []
for email in emails:
    try:
        normalized = module.TeacherLoginSchema(email=email, mat_khau='fixture-password').email
        cases.append({'input': email, 'accepted': True, 'normalized': str(normalized)})
    except ValueError:
        cases.append({'input': email, 'accepted': False, 'normalized': None})
target = root / 'backend/src/test/resources/auth/email-cases.json'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps({'syntheticOnly': True, 'source': '.tools/railway-publish/backend/app/schemas/auth.py', 'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'generator': {name: importlib.metadata.version(name) for name in ('pydantic', 'email-validator')}, 'cases': cases}, ensure_ascii=False, indent=2), encoding='utf-8')
print('Generated synthetic legacy EmailStr cases:', len(cases))
