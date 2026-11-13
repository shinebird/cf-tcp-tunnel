#!/usr/bin/env python3
"""Offline resource regression checks; NOT Android Lint, AAPT, or a device test.

Usage: python tests/check_theme_resources.py
The version-resolution helper models only this project's values[-vNN] styles;
it deliberately rejects other qualifiers instead of pretending to model Android.
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
ANDROID = '{http://schemas.android.com/apk/res/android}'
TOOLS = '{http://schemas.android.com/tools}'
NAV = 'android:windowLightNavigationBar'
FRAMEWORK_PARENT = 'android:style/Theme.Material.Light.NoActionBar'
SHARED = {
    'android:fontFamily': 'sans',
    'android:colorAccent': '#176BDB',
    'android:windowLightStatusBar': 'true',
    'android:windowActionModeOverlay': 'true',
    'android:windowBackground': '#F4F6FA',
}

def variants():
    result = {}
    for directory in sorted(RES.glob('values*')):
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob('*.xml')):
            styles = ET.parse(path).getroot().findall('style')
            if not styles:
                continue
            match = re.fullmatch(r'values(?:-v(\d+))?', directory.name)
            if match is None:
                raise AssertionError('Extend this test for qualifier: ' + directory.name)
            level = int(match.group(1) or 0)
            for style in styles:
                name = style.attrib['name']
                bucket = result.setdefault(name, {})
                if level in bucket:
                    raise AssertionError('Duplicate style: ' + name)
                names = [item.attrib['name'] for item in style.findall('item')]
                if len(names) != len(set(names)):
                    raise AssertionError('Duplicate style item: ' + name)
                bucket[level] = (
                    style.attrib.get('parent', ''),
                    {item.attrib['name']: (item.text or '').strip()
                     for item in style.findall('item')},
                )
    return result

def resolve_local_theme(all_styles, name, api, visited=None):
    visited = set() if visited is None else set(visited)
    if name in visited:
        raise AssertionError('Style inheritance cycle: ' + name)
    visited.add(name)
    available = [level for level in all_styles[name] if level <= api]
    if not available:
        raise AssertionError('Missing fallback style: ' + name)
    parent, own = all_styles[name][max(available)]
    if parent.startswith('@style/'):
        parent = parent[len('@style/'):]
    if parent.startswith('android:'):
        terminal, inherited = parent, {}
    else:
        terminal, inherited = resolve_local_theme(all_styles, parent, api, visited)
    return terminal, {**inherited, **own}

class ThemeResourceTests(unittest.TestCase):
    def test_all_resource_xml_is_well_formed(self):
        for path in RES.rglob('*.xml'):
            with self.subTest(path=str(path.relative_to(ROOT))):
                ET.parse(path)

    def test_shared_values_preserved(self):
        parent, items = variants()['AppThemeBase'][0]
        self.assertEqual(parent, FRAMEWORK_PARENT)
        self.assertEqual(items, SHARED)

    def test_default_theme_inherits_shared_base(self):
        self.assertEqual(variants()['AppTheme'][0], ('AppThemeBase', {}))

    def test_navigation_attribute_is_version_gated(self):
        definitions = []
        for path in RES.glob('values*/*.xml'):
            for item in ET.parse(path).getroot().iter('item'):
                if item.attrib.get('name') == NAV:
                    definitions.append(path.parent.name)
                    self.assertEqual((item.text or '').strip(), 'true')
        self.assertEqual(definitions, ['values-v27'])
        self.assertEqual(variants()['AppTheme'][27], ('AppThemeBase', {NAV: 'true'}))

    def test_api26_fallback_retains_shared_items(self):
        parent, items = resolve_local_theme(variants(), 'AppTheme', 26)
        self.assertEqual(parent, FRAMEWORK_PARENT)
        self.assertEqual(items, SHARED)
        self.assertNotIn(NAV, items)

    def test_api27_and_later_retain_shared_items(self):
        for api in (27, 28, 30, 36, 37):
            with self.subTest(api=api):
                parent, items = resolve_local_theme(variants(), 'AppTheme', api)
                self.assertEqual(parent, FRAMEWORK_PARENT)
                self.assertEqual(items, {**SHARED, NAV: 'true'})

    def test_manifest_and_sdk_contract(self):
        manifest = ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
        self.assertEqual(manifest.find('application').attrib[ANDROID+'theme'], '@style/AppTheme')
        gradle = (ROOT/'app/build.gradle').read_text(encoding='utf-8')
        self.assertRegex(gradle, r'(?m)^\s*minSdk\s+26\s*$')
        self.assertRegex(gradle, r'(?m)^\s*targetSdk\s+36\s*$')
        self.assertIn("versionName '1.1.2-native'", gradle)
        self.assertIn('abortOnError true', gradle)
        self.assertNotIn('abortOnError false', gradle)

    def test_newapi_not_suppressed(self):
        lint = ET.parse(ROOT/'app/lint.xml').getroot()
        for issue in lint.findall('issue'):
            self.assertNotIn('NewApi', issue.attrib.get('id', '').split(','))
        for path in RES.glob('values*/*.xml'):
            for element in ET.parse(path).getroot().iter():
                self.assertNotIn(TOOLS+'targetApi', element.attrib)
                self.assertNotIn('NewApi', element.attrib.get(TOOLS+'ignore', '').split(','))

if __name__ == '__main__':
    unittest.main(verbosity=2)
