import unittest
from io import BytesIO
from pypdf import PdfReader
import check_large_mixed as mixed

class LargeMixedTests(unittest.TestCase):
    def test_fixture_is_reproducible_and_has_page_markers(self):
        first=mixed.fixture(2)
        self.assertEqual(first,mixed.fixture(2))
        reader=PdfReader(BytesIO(first))
        self.assertEqual(2,len(reader.pages))
        self.assertIn("CAPACITY_PAGE_0002",reader.pages[-1].extract_text())
    def test_embedded_size_fixture_remains_a_valid_pdf(self):
        data=mixed.fixture(1,1024)
        reader=PdfReader(BytesIO(data))
        self.assertEqual(1,len(reader.pages))
        self.assertEqual(1024,len(reader.attachments["capacity-payload.bin"][0]))
    def test_fixture_limits(self):
        for count in [0,202]:
            with self.assertRaises(ValueError): mixed.fixture(count)
    def test_fields_are_multipart_not_query_parameters(self):
        data,boundary=mixed.multipart(b"%PDF-fixture",{"pages":"1,2","pattern":"marker"})
        self.assertIn(b'name="pages"\r\n\r\n1,2\r\n',data)
        self.assertTrue(data.endswith(("--"+boundary+"--\r\n").encode()))
    def test_wrong_status_or_code_is_not_a_passing_check(self):
        with self.assertRaises(AssertionError): mixed.require({"operation":"info","status":503},200)
        with self.assertRaises(AssertionError): mixed.require({"operation":"info","status":413,"code":"WRONG"},413,"PAGE_LIMIT")
    def test_success_http_with_wrong_content_is_rejected(self):
        with self.assertRaises(AssertionError): mixed.validate("info",b'{"pageCount":1}',200)
        with self.assertRaises(AssertionError): mixed.validate("text/search",b'[]',200)
        with self.assertRaises(AssertionError): mixed.expect(False,"fixture failure")

if __name__=="__main__": unittest.main()
