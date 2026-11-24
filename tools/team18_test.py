"""Offline regression tests for the fixed public acquisition boundary."""
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
from urllib.error import HTTPError, URLError

import team18_acquire as a


class Response(io.BytesIO):
    status = 200


class AcquisitionTests(unittest.TestCase):
    def invoke(self, raw=None, error=None, kind="submissions"):
        opener = Mock()
        if error:
            opener.open.side_effect = error
        else:
            opener.open.return_value = Response(raw)
        with tempfile.TemporaryDirectory() as folder:
            result = a.acquire_one(Path(folder), "AAPL", kind, opener)
            files = {p.name: p.read_bytes() for p in Path(folder).iterdir()}
        return result, files, opener

    def test_raw_success_hash_and_method(self):
        raw = json.dumps({"cik": "320193", "filings": {"recent": {"accessionNumber": ["0000320193-25-000001"], "filingDate": ["2025-01-01"], "acceptanceDateTime": ["2025-01-01T12:00:00Z"]}, "files": [{"name": "older"}]}}).encode()
        result, files, opener = self.invoke(raw)
        self.assertEqual(result["status"], "ACQUIRED_RAW_PARTIAL")
        self.assertEqual(result["sha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(files["AAPL_submissions.json"], raw)
        self.assertFalse(result["historical_provider_snapshot"])
        self.assertEqual(result["summary"]["additional_history_files_not_downloaded"], 1)
        request = opener.open.call_args.args[0]
        self.assertEqual(request.get_method(), "GET")
        self.assertEqual(request.full_url, a.endpoint("AAPL", "submissions"))
        self.assertNotIn("Authorization", request.headers)

    def test_http_failure_keeps_no_error_body(self):
        result, files, _ = self.invoke(error=HTTPError(a.endpoint("AAPL", "submissions"), 403, "private debug", {}, None))
        self.assertEqual(result["http_status"], 403)
        self.assertEqual(result["error"], "HTTP_ERROR")
        self.assertNotIn("private", json.dumps(result))
        self.assertEqual(files, {})

    def test_network_failure_is_not_fundamental_lookup_failure(self):
        result, files, _ = self.invoke(error=URLError("sensitive proxy details"))
        self.assertEqual(result["status"], "FAILED")
        self.assertNotIn("sensitive", json.dumps(result))
        self.assertNotIn("lookup_status", result)
        self.assertEqual(files, {})

    def test_network_error_code_without_sensitive_message(self):
        result, files, _ = self.invoke(error=URLError(OSError(13, "sensitive proxy details")))
        self.assertEqual(result["errno"], 13)
        self.assertEqual(result["reason_type"], "PermissionError")
        self.assertNotIn("sensitive", json.dumps(result))
        self.assertEqual(files, {})

    def test_response_size_bound(self):
        from unittest.mock import patch
        with patch.object(a, "MAX_BYTES", 8):
            result, files, _ = self.invoke(b"0123456789")
        self.assertEqual(result["error"], "INVALID_PROVIDER_RESPONSE")
        self.assertEqual(files, {})

    def test_wrong_company_rejected(self):
        result, files, _ = self.invoke(b'{"cik": 789019, "filings": {}}')
        self.assertEqual(result["error"], "INVALID_PROVIDER_RESPONSE")
        self.assertEqual(files, {})

    def test_html_rejected(self):
        result, files, _ = self.invoke(b'<html>403</html>')
        self.assertEqual(result["error"], "INVALID_PROVIDER_RESPONSE")
        self.assertEqual(files, {})

    def test_current_aggregate_not_marked_point_in_time(self):
        rows = [{"filed": day, "val": value, "end": "2024-12-31", "accn": "0000320193-25-000001"} for day, value in [("2025-01-01", 1), ("2099-01-01", 2)]]
        raw = json.dumps({"cik": 320193, "facts": {"us-gaap": {"EPS": {"units": {"USD/shares": rows}}}}}).encode()
        result, _, _ = self.invoke(raw, kind="companyfacts")
        self.assertEqual(result["summary"]["fact_record_count"], 2)
        self.assertFalse(result["summary"]["point_in_time_selected"])
        self.assertFalse(result["historical_provider_snapshot"])

    def test_empty_facts_and_filings_rejected(self):
        for kind, document in [
            ("companyfacts", {"cik": 320193, "facts": {"us-gaap": {}}}),
            ("companyfacts", {"cik": 320193, "facts": {"us-gaap": {"EPS": {"units": {}}}}}),
            ("submissions", {"cik": 320193, "filings": {"recent": {"accessionNumber": []}}})
        ]:
            with self.subTest(kind=kind, document=document):
                result, files, _ = self.invoke(json.dumps(document).encode(), kind=kind)
                self.assertEqual(result["error"], "INVALID_PROVIDER_RESPONSE")
                self.assertEqual(files, {})

    def test_missing_acceptance_timestamp_rejected(self):
        raw = json.dumps({"cik": 320193, "filings": {"recent": {"accessionNumber": ["0000320193-25-000001"], "filingDate": ["2025-01-01"]}}}).encode()
        result, files, _ = self.invoke(raw)
        self.assertEqual(result["error"], "INVALID_PROVIDER_RESPONSE")
        self.assertEqual(files, {})

    def test_fixed_endpoint_only(self):
        with self.assertRaises(ValueError):
            a.endpoint("AAPL", "https://example.com")
        with self.assertRaises(KeyError):
            a.endpoint("OTHER", "submissions")

    def test_redirect_refused(self):
        with self.assertRaises(HTTPError):
            a.NoRedirect().redirect_request(Mock(full_url="https://data.sec.gov/test"), None, 302, "redirect", {}, "https://other.example/")


if __name__ == "__main__":
    unittest.main()
