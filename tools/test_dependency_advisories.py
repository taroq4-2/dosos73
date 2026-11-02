"""Regression checks for failure handling in the release advisory gate (no network)."""
import datetime as dt
import unittest
from check_dependency_advisories import audit, queries_for

QUERY = {"package": {"ecosystem": "Maven", "name": "example:test"}, "version": "1.0"}
TODAY = dt.date(2026, 11, 2)


class AdvisoryGateTest(unittest.TestCase):
    def test_no_matches_is_scoped_pass(self):
        self.assertEqual("PASS_SCOPED_LOOKUP", audit([QUERY], [], TODAY,
                         lambda *args: {"results": [{}]})["result"])

    def test_new_finding_blocks(self):
        def server(endpoint, payload=None):
            return {"results": [{"vulns": [{"id": "GHSA-test"}]}]} if endpoint == "querybatch" else {"id": "GHSA-test"}
        result = audit([QUERY], [], TODAY, server)
        self.assertEqual("BLOCKED_UNREVIEWED", result["result"])
        self.assertIn("GHSA-test", result["advisories"])

    def test_truncated_batch_is_error(self):
        with self.assertRaises(ValueError):
            audit([QUERY], [], TODAY, lambda *args: {"results": []})

    def test_pagination_is_not_silently_accepted(self):
        with self.assertRaises(ValueError):
            audit([QUERY], [], TODAY, lambda *args: {"results": [{"next_page_token": "more"}]})

    def test_network_error_propagates(self):
        def offline(*args):
            raise TimeoutError()
        with self.assertRaises(TimeoutError):
            audit([QUERY], [], TODAY, offline)

    def test_expired_or_wrong_version_review_does_not_pass(self):
        def server(endpoint, payload=None):
            return {"results": [{"vulns": [{"id": "GHSA-test"}]}]} if endpoint == "querybatch" else {"id": "GHSA-test"}
        review = {"id": "GHSA-test", **QUERY, "status": "not_affected", "owner": "reviewer",
                  "reason": "Not called", "evidence": "test", "expires": "2026-11-01"}
        self.assertEqual("BLOCKED_UNREVIEWED", audit([QUERY], [review], TODAY, server)["result"])
        review["expires"] = "2026-11-03"
        self.assertEqual("PASS_SCOPED_LOOKUP", audit([QUERY], [review], TODAY, server)["result"])
        review["version"] = "2.0"
        self.assertEqual("BLOCKED_UNREVIEWED", audit([QUERY], [review], TODAY, server)["result"])

    def test_empty_inventory_rejected(self):
        with self.assertRaises(ValueError):
            queries_for([], {"pypi": []})


if __name__ == "__main__":
    unittest.main()
