#!/usr/bin/env python3
"""Local development only. Stop every backend before --delete; never cleans linked files."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time

NAME = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(blob|part)$")

def eligible(path, referenced, now):
    return (NAME.fullmatch(path.name) and not path.is_symlink() and path.is_file()
            and path.stem not in referenced and now - path.stat().st_mtime >= 86400)

def candidates(root, referenced, now):
    return sorted(p for p in root.iterdir() if eligible(p, referenced, now))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', default=os.environ.get('WECALL_ATTACHMENT_DIR', str(Path.home()/'.wecall/attachments')))
    parser.add_argument('--delete', action='store_true')
    parser.add_argument('--confirm-all-backends-stopped', action='store_true')
    parser.add_argument('--backend-port', type=int, default=8080)
    args = parser.parse_args()
    root = Path(args.directory).absolute()
    if any(p.is_symlink() for p in (root, *root.parents)):
        parser.error('Symbolic directory is not allowed')
    if not root.is_dir():
        parser.error('Attachment directory does not exist')
    if args.delete:
        if not args.confirm_all_backends_stopped:
            parser.error('--delete requires --confirm-all-backends-stopped')
        check = subprocess.run(['lsof', '-nP', f'-iTCP:{args.backend_port}', '-sTCP:LISTEN', '-t'], capture_output=True, text=True)
        if check.returncode != 1 or check.stdout.strip():
            parser.error('Backend port is listening or could not be checked; stop every backend first')
    # Database failures abort before any deletion. This tool targets the repository's local compose DB only.
    result = subprocess.run(['docker','compose','exec','-T','postgres','psql','-U','wecall','-d','wecall','-At','-v','ON_ERROR_STOP=1','-c','SELECT id FROM evidence_attachment UNION SELECT id FROM source_document'], cwd=Path(__file__).resolve().parents[1], check=True, capture_output=True, text=True)
    referenced = {line.strip() for line in result.stdout.splitlines() if line.strip()}
    found = candidates(root, referenced, time.time())
    for path in found:
        print(('DELETE ' if args.delete else 'CANDIDATE ') + path.name)
        if args.delete:
            # Recheck age/name/symlink immediately before unlink; backend must remain stopped.
            if eligible(path, referenced, time.time()):
                path.unlink()
    print(f'{len(found)} candidate(s); mode=' + ('delete' if args.delete else 'dry-run'))

if __name__ == '__main__':
    main()
