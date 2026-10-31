import importlib.util
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('release_check', ROOT / 'scripts/verify-profit-release.py')
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class ReleaseArtifactTest(unittest.TestCase):
    def fixture(self):
        # This is a packaging fixture, not runnable bytecode or an HTTP test.
        files = {check.PREFIX + name: b'/scope-settings /scope-catalog' for name in check.REQUIRED}
        resources = ROOT / 'src/main/resources'
        for file in (resources / 'db/wp').rglob('V*__*.sql'):
            files[check.PREFIX + file.relative_to(resources).as_posix()] = file.read_bytes()
        return files

    def verify(self, files):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.jar'
            with ZipFile(path, 'w') as jar:
                for name, data in files.items():
                    jar.writestr(name, data)
            return check.verify(path)

    def test_current_packaging_passes(self):
        self.assertEqual([], self.verify(self.fixture()))

    def test_missing_scope_controller_fails(self):
        files = self.fixture()
        del files[check.PREFIX + check.REQUIRED[0]]
        self.assertTrue(any('Missing' in x for x in self.verify(files)))

    def test_duplicate_common_v12_fails(self):
        files = self.fixture()
        vendor = next(x for x in files if '/vendor/mariadb/V12__' in x)
        files[vendor.replace('/vendor/mariadb/', '/migration/')] = files[vendor]
        self.assertIn('Expected exactly one packaged SQL V12', self.verify(files))

    def test_applied_checksum_must_not_change(self):
        files = self.fixture()
        path = next(x for x in files if '/V20__' in x)
        files[path] += b'-- altered migration\n'
        self.assertIn('Applied SQL V20 checksum changed', self.verify(files))

    def test_class_without_route_fails(self):
        files = self.fixture()
        files[check.PREFIX + check.REQUIRED[0]] = b'/scope-settings'
        self.assertIn('Missing controller mapping /scope-catalog', self.verify(files))


if __name__ == '__main__':
    unittest.main()
