import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('data29',Path(__file__).with_name('team29_data_prepare.py'))
d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)

class Tests(unittest.TestCase):
    def raw(self):
        start=d.base.epoch('2026-11-02')+14*3600+30*60
        return {'chart':{'error':None,'result':[{'meta':{'symbol':'AAPL','currency':'USD','exchangeTimezoneName':'America/New_York'},
            'timestamp':[start+i*300 for i in range(78)],'indicators':{'quote':[{'open':[100]*78,'high':[102]*78,'low':[99]*78,'close':[101]*78,'volume':[50]*78}]}}]}}
    def test_dst_boundaries(self):
        cases=[('2026-10-30',13,30,9,30),('2026-11-02',14,30,9,30),('2025-03-07',14,30,9,30),('2025-03-10',13,30,9,30)]
        for day,h,m,lh,lm in cases:
            got=d.ny_time(d.base.epoch(day)+h*3600+m*60)
            self.assertEqual((got.hour,got.minute),(lh,lm))
        with self.assertRaises(ValueError):d.ny_time(d.base.epoch('2023-01-01'))
    def test_complete_session_and_no_imputed_bar(self):
        raw=self.raw();rows,sessions,rejected=d.intraday(json.dumps(raw),'AAPL','5m')
        self.assertEqual((len(rows),sessions[0]['complete'],rejected),(78,True,[]))
        raw['chart']['result'][0]['indicators']['quote'][0]['open'][5]=None
        rows,sessions,rejected=d.intraday(json.dumps(raw),'AAPL','5m')
        self.assertEqual((len(rows),sessions[0]['complete'],len(rejected)),(77,False,1))
    def test_duplicate_wrong_timezone_and_nonfinite_rejected(self):
        mutations=[lambda r:r['meta'].update(exchangeTimezoneName='UTC'),lambda r:r['timestamp'].__setitem__(1,r['timestamp'][0]),lambda r:r['indicators']['quote'][0]['open'].__setitem__(1,float('nan'))]
        for mutate in mutations:
            raw=self.raw();mutate(raw['chart']['result'][0])
            with self.assertRaises(ValueError):d.intraday(json.dumps(raw),'AAPL','5m')
    def test_outside_regular_and_future_cutoff_not_accepted(self):
        raw=self.raw();r=raw['chart']['result'][0];r['timestamp'][77]=d.base.epoch('2026-11-23')+14*3600+30*60
        rows,sessions,rejected=d.intraday(json.dumps(raw),'AAPL','5m')
        self.assertEqual(len(rows),77);self.assertFalse(sessions[0]['complete'])
    def test_daily_fractional_actions_rejected(self):
        with self.assertRaises(ValueError):d.base.nominal_rows([{'split_numerator':3,'split_denominator':2}])
    def test_empty_partial_not_pass(self):
        with tempfile.TemporaryDirectory() as name:
            root=Path(name)
            for mode in d.WINDOWS:
                f=root/mode;f.mkdir();(f/'manifest.json').write_text(json.dumps({'status':'partial','files':[]}))
            (root/'daily/daily.csv').write_text('date,symbol\n')
            result=d.verify(root)
            self.assertTrue(all(result[m]['status']=='PARTIAL' for m in d.WINDOWS))
    def test_hash_normalization_mismatch_rejected(self):
        with self.assertRaises(ValueError):d.compare([{'open':'101'}],[{'open':100}])

if __name__=='__main__':unittest.main()
