"""Generate server-only demo secrets, never printing their values."""
import os
from pathlib import Path
import secrets
import sys
host = sys.argv[1]
if not host or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789.-' for c in host):
    raise SystemExit('Expected public hostname')
path = Path(__file__).parent / '.env'
if path.exists():
    raise SystemExit('Existing environment preserved')
values = {
    'PUBLIC_HOST': host,
    'DB_PASSWORD': secrets.token_hex(32),
    'WECALL_AI_SERVICE_TOKEN': secrets.token_hex(32),
    'WECALL_BOOTSTRAP_USERNAME': 'demo-reviewer',
    'WECALL_BOOTSTRAP_PASSWORD': secrets.token_hex(16),
    'WECALL_BOOTSTRAP_DISPLAY_NAME': '합성 시연 검토자',
    'WECALL_BOOTSTRAP_OPERATOR_USERNAME': 'demo-operator',
    'WECALL_BOOTSTRAP_OPERATOR_PASSWORD': secrets.token_hex(16),
    'WECALL_BOOTSTRAP_OPERATOR_DISPLAY_NAME': '합성 시연 담당자',
}
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as stream:
    stream.write(''.join(f'{k}={v}\n' for k,v in values.items()))
print('Server environment created with private permissions')
