"""Offline ingestion guard tests. No network, no credentials."""
import json
import tempfile
import unittest
from pathlib import Path
from team21_fetch import normalize, epoch, validate_calendar, url, write_csv, NoRedirect, nominal_rows


def sample():
    return {'chart': {'error': None, 'result': [{'meta': {'symbol': 'SPY', 'currency': 'USD'},
        'timestamp': [epoch('2006-01-03'), epoch('2006-01-04')],
        'indicators': {'quote': [{'open': [10, 11], 'high': [12, 12], 'low': [9, 10],
                                'close': [11, 11], 'volume': [100, 100]}],
                       'adjclose': [{'adjclose': [10, 10]}]},
        'events': {'dividends': {'x': {'date': epoch('2006-01-04'), 'amount': .2}},
                   'splits': {'x': {'date': epoch('2006-01-04'), 'numerator': 2, 'denominator': 1}}}}]}}


class IngestionTest(unittest.TestCase):
    def test_preserves_price_and_events(self):
        rows = normalize(json.dumps(sample()), 'SPY')
        self.assertEqual(rows[0]['open'], 10)
        self.assertEqual(rows[1]['dividend'], .2)
        self.assertEqual(rows[1]['split_numerator'], 2)

    def test_invalid_values(self):
        for value in (None, float('nan'), -1, True):
            data = sample()
            data['chart']['result'][0]['indicators']['quote'][0]['close'][0] = value
            with self.assertRaises(ValueError): normalize(json.dumps(data), 'SPY')

    def test_wrong_identity(self):
        with self.assertRaises(ValueError): normalize(json.dumps(sample()), 'XLB')

    def test_date_order_and_holes(self):
        rows = normalize(json.dumps(sample()), 'SPY')
        with self.assertRaises(ValueError): validate_calendar({'SPY': rows, 'XLB': rows[:1]})
        data = sample()
        data['chart']['result'][0]['timestamp'].reverse()
        with self.assertRaises(ValueError): normalize(json.dumps(data), 'SPY')

    def test_output_no_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'data.csv'
            write_csv(path, normalize(json.dumps(sample()), 'SPY'))
            with self.assertRaises(FileExistsError): write_csv(path, [])

    def test_network_allowlist(self):
        with self.assertRaises(ValueError): url('http://localhost')
        with self.assertRaises(ValueError): NoRedirect().redirect_request(None, None, 302, '', {}, 'https://evil.example')

    def test_nominal_reconstruction_excludes_current_split(self):
        rows = nominal_rows(normalize(json.dumps(sample()), 'SPY'))
        self.assertEqual(rows[0]['trade_open'], 20)
        self.assertEqual(rows[1]['trade_open'], 11)
        self.assertEqual(rows[1]['trade_dividend'], .2)

    def test_spin_off_rejected(self):
        rows = normalize(json.dumps(sample()), 'SPY')
        rows[1]['split_numerator'] = 1.15
        with self.assertRaises(ValueError): nominal_rows(rows)


if __name__ == '__main__':
    unittest.main()
