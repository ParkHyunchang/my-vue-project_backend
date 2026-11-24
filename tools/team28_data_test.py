import copy
import importlib.util
import json
import unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('data28',Path(__file__).with_name('team28_data_prepare.py'))
data=importlib.util.module_from_spec(spec)
spec.loader.exec_module(data)


class DataTests(unittest.TestCase):
    def raw(self):
        return {'chart':{'result':[{'meta':{'symbol':'IWM','currency':'USD'},
            'timestamp':[data.collector.epoch('2006-03-01'),data.collector.epoch('2006-03-02')],
            'indicators':{'quote':[{'open':[100,101],'high':[103,104],'low':[99,100],'close':[102,103],'volume':[20,30]}],
                          'adjclose':[{'adjclose':[90,91]}]}}],'error':None}}

    def test_quote_not_dividend_adjusted_for_execution(self):
        rows=data.collector.normalize(json.dumps(self.raw()),'IWM')
        nominal=data.collector.nominal_rows(rows)
        self.assertEqual(nominal[0]['trade_open'],100)
        self.assertEqual(nominal[0]['adj_close'],90)

    def test_wrong_symbol_missing_and_nonfinite_rejected(self):
        for mutate in (lambda r:r['chart']['result'][0]['meta'].update(symbol='BAD'),
                       lambda r:r['chart']['result'][0]['indicators']['quote'][0]['open'].__setitem__(0,None),
                       lambda r:r['chart']['result'][0]['indicators']['quote'][0]['open'].__setitem__(0,float('nan'))):
            raw=self.raw(); mutate(raw)
            with self.assertRaises(ValueError):data.collector.normalize(json.dumps(raw),'IWM')

    def test_no_calendar_fill_or_missing_universe(self):
        rows=data.collector.normalize(json.dumps(self.raw()),'IWM')
        rows[-1]['date']='2026-11-20'
        fixed={s:copy.deepcopy(rows) for s in data.SYMBOLS}
        self.assertEqual(len(data.validate(fixed)),2)
        fixed['IWM'][0]['date']='2006-03-02'
        with self.assertRaises(ValueError):data.validate(fixed)
        del fixed['IWM']
        with self.assertRaises(ValueError):data.validate(fixed)

    def test_split_reconstruction_and_unsupported_entitlement(self):
        rows=data.collector.normalize(json.dumps(self.raw()),'IWM')
        rows[1]['split_numerator']=2
        nominal=data.collector.nominal_rows(rows)
        self.assertEqual(nominal[0]['trade_open'],200)
        self.assertEqual(nominal[1]['trade_open'],101)
        for numerator,denominator in ((1,2),(3,2)):
            rows[1].update(split_numerator=numerator,split_denominator=denominator)
            with self.assertRaises(ValueError):data.collector.nominal_rows(rows)

    def test_fixed_allowlist_rejects_external_path(self):
        with self.assertRaises(ValueError):data.collector.url('https://example.com')


if __name__=='__main__':unittest.main()
