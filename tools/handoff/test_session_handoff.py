import tempfile
import unittest
from pathlib import Path
from session_handoff import make, save, read

class HandoffTests(unittest.TestCase):
    def test_round_trip(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'checkpoint.json'
            save(p,make('task-1','Fix Bridge','Check UI',verified=['Build passed']))
            self.assertTrue(read(p)['resume_requires_reverification'])
            self.assertEqual(read(p)['verified_results'],['Build passed'])
            self.assertEqual(p.stat().st_mode & 0o777,0o600)
    def test_reject_secret(self):
        with self.assertRaises(ValueError): make('task-1','Bearer abc','Continue')
    def test_reject_state(self):
        with self.assertRaises(ValueError): make('task-1','Goal','Continue','UNKNOWN')
    def test_schema(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'x.json'
            p.write_text('{"schema_version":99}')
            with self.assertRaises(ValueError): read(p)

if __name__=='__main__':
    unittest.main()
