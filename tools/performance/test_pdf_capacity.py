import io
import unittest
from unittest.mock import patch
import check_pdf_capacity as capacity


class CapacityTests(unittest.TestCase):
    def test_origin_is_loopback_only_and_has_no_credentials_or_path(self):
        self.assertEqual("http://127.0.0.1:5173", capacity.base_url("http://127.0.0.1:5173/"))
        for value in ["https://example.com", "http://user:secret@localhost", "http://localhost/api", "http://localhost?x=1", "http://localhost#x"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                capacity.base_url(value)

    def test_nearest_rank_percentiles(self):
        self.assertEqual(5, capacity.percentile(list(range(1, 11)), .5))
        self.assertEqual(10, capacity.percentile(list(range(1, 11)), .95))
        with self.assertRaises(ValueError):
            capacity.percentile([], .5)

    def test_multipart_fixture_is_binary_safe(self):
        pdf = b"%PDF-\x00\xff\r\n"
        body, boundary = capacity.request_body(pdf)
        self.assertIn(pdf, body)
        self.assertTrue(body.endswith(("\r\n--" + boundary + "--\r\n").encode()))
        self.assertIn(b'name="file"', body)

    def test_invalid_counts_and_non_pdf_do_not_send_requests(self):
        with patch.object(capacity, "probe") as probe:
            for samples, parallel, rounds in [(0, 4, 2), (1, 9, 1), (1, 4, 11)]:
                with self.assertRaises(ValueError):
                    capacity.verify("http://localhost", b"%PDF-fixture", samples, parallel, rounds)
            with self.assertRaises(ValueError):
                capacity.verify("http://localhost", b"not-pdf", 1, 4, 1)
            probe.assert_not_called()

    def test_warmup_rejection_is_not_a_passing_baseline(self):
        with patch.object(capacity, "probe", return_value={"status": 429, "elapsed_ms": 1}):
            with self.assertRaises(ValueError):
                capacity.verify("http://localhost", b"%PDF-fixture", 1, 4, 1)


    def test_redirects_are_not_followed(self):
        with self.assertRaises(ValueError):
            capacity.RejectRedirects().redirect_request(None, None, 302, "redirect", {}, "http://example.com")

    def test_unexpected_http_status_is_not_a_passing_probe(self):
        response = io.BytesIO(b'{"status":500,"code":"INTERNAL_ERROR"}')
        response.status = 500
        with patch.object(capacity._OPENER, "open", return_value=response), self.assertRaises(ValueError):
            capacity.probe("http://localhost", b"fixture", "boundary")

    def test_non_metadata_success_is_rejected(self):
        response = io.BytesIO(b'{"status":"ok"}')
        response.status = 200
        with patch.object(capacity._OPENER, "open", return_value=response), self.assertRaises(ValueError):
            capacity.probe("http://localhost", b"fixture", "boundary")


if __name__ == "__main__":
    unittest.main()
