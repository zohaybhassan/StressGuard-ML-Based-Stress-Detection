import csv
import io
import pathlib
import sys
import unittest

LAMBDA_DIR = pathlib.Path(__file__).parents[1] / "lambda"
sys.path.insert(0, str(LAMBDA_DIR))

from report_csv import FIELDNAMES, render_csv


class ReportCsvTests(unittest.TestCase):
    def test_quotes_formula_like_and_comma_values(self):
        row = {name: None for name in FIELDNAMES}
        row.update({"record_type": "alert", "alert_reason": '=1+1,"quoted"'})
        parsed = list(csv.DictReader(io.StringIO(render_csv([row]).decode("utf-8"))))
        self.assertEqual(parsed[0]["alert_reason"], "'=1+1,\"quoted\"")
        self.assertEqual(parsed[0]["confidence"], "")


if __name__ == "__main__":
    unittest.main()
