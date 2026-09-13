import tempfile
import time
import os
import unittest
from pathlib import Path
from cleanup_attachment_orphans import candidates

class CleanupTests(unittest.TestCase):
    def test_only_old_unreferenced_uuid_files_are_candidates(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);now=time.time()
            ids=['00000000-0000-0000-0000-00000000000'+str(i) for i in range(4)]
            for i in range(3):
                path=root/(ids[i]+'.blob');path.write_bytes(b'x');os.utime(path,(now-90000,now-90000))
            (root/(ids[2]+'.blob')).touch()
            (root/'unrelated.txt').write_text('preserve')
            (root/(ids[3]+'.part')).symlink_to(root/(ids[0]+'.blob'))
            self.assertEqual([p.name for p in candidates(root,{ids[0]},now)],[ids[1]+'.blob'])

if __name__=='__main__':unittest.main()
