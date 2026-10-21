"""Synthetic Java/python-jose interoperability, stdin/stdout IPC only; never loads .env.

Invoked by PostgresAuthIT. The key is a public test constant, not an application secret.
Tokens exist in subprocess pipes only and are not printed by the test runner.
"""
import json
import sys
from datetime import datetime, timedelta, timezone
from jose import jwt

KEY = 'synthetic-jwt-test-key-at-least-32-bytes'
request = json.load(sys.stdin)
if request['operation'] == 'issue':
    now = datetime.now(timezone.utc)
    print(jwt.encode({'sub': request['id'], 'iat': now, 'exp': now + timedelta(minutes=30)}, KEY, algorithm='HS256'))
elif request['operation'] == 'verify':
    claims = jwt.decode(request['token'], KEY, algorithms=['HS256'], options={'require_exp': True, 'require_sub': True})
    print(json.dumps({'sub': claims['sub'], 'lifetime': claims['exp'] - claims['iat']}))
else:
    raise SystemExit(2)
