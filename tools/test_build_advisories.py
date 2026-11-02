"""Regression checks for parsing actual build-tool pins without network access."""
import unittest
from check_build_advisories import queries_for_build, verify_kapt_source

WRAPPER = 'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.6-bin.zip\n'
PLUGINS = '''plugins {
 id("com.android.application") version "8.10.1" apply false
 id("org.jetbrains.kotlin.android") version "2.0.21" apply false
 id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}'''


class BuildPinsTest(unittest.TestCase):
    def test_reads_actual_pins(self):
        queries = queries_for_build(WRAPPER, PLUGINS)
        self.assertEqual(['8.14.6', '8.10.1', '2.0.21', '2.0.21'], [q['version'] for q in queries])
        self.assertEqual('org.gradle:gradle-core', queries[0]['package']['name'])

    def test_unpinned_wrapper_rejected(self):
        with self.assertRaises(ValueError):
            queries_for_build(WRAPPER.replace('8.14.6', 'latest'), PLUGINS)

    def test_missing_plugin_rejected(self):
        with self.assertRaises(ValueError):
            queries_for_build(WRAPPER, PLUGINS.replace('id("com.android.application")', 'id("another.plugin")'))

    def test_ambiguous_declarations_rejected(self):
        with self.assertRaises(ValueError):
            queries_for_build(WRAPPER, PLUGINS + '\nid("com.android.application") version "8.10.2"')

    def test_kapt_enablement_invalidates_review(self):
        for declaration in ('id("org.jetbrains.kotlin.kapt")', 'apply(plugin = "kotlin-kapt")',
                            'kotlin("kapt")', 'kapt("an.annotation:processor:1")'):
            with self.subTest(declaration=declaration), self.assertRaises(ValueError):
                verify_kapt_source(declaration)

    def test_guard_code_does_not_apply_kapt(self):
        verify_kapt_source('check(!candidate.pluginManager.hasPlugin("org.jetbrains.kotlin.kapt"))')


if __name__ == '__main__':
    unittest.main()
