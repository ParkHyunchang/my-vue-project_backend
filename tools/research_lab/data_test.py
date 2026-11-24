import copy
import unittest
from research_lab.data import append_market, check_append


def market(count=3):
    return {'data':{s:[dict(date=f'2026-01-0{i+2}',open=100+i,close=100+i,high=102+i,low=99+i,
         volume=1000,dividend=0,adj_close=90+i,split_numerator=1,split_denominator=1) for i in range(count)]
         for s in ('SPY','QQQ','GLD','IEF')},'benchmark':{f'2026-01-0{i+2}':1000+i for i in range(count)}}


class DataTests(unittest.TestCase):
    def test_uniform_dividend_rebase_preserves_history(self):
        old=market(2);fresh=market(3)
        for rows in fresh['data'].values():
            for r in rows:r['adj_close']*=.97
        before=copy.deepcopy(old)
        out=append_market(old,fresh)
        self.assertEqual(old,before)
        for symbol in old['data']:
            self.assertEqual(out['data'][symbol][:2],old['data'][symbol])
            self.assertAlmostEqual(out['data'][symbol][-1]['adj_close'],92)

    def test_nonuniform_revision_and_raw_correction_rejected(self):
        for field in ('close','adj_close','dividend','volume','split_numerator'):
            old=market(2);fresh=market(3);fresh['data']['SPY'][0][field]+=1
            with self.assertRaises(ValueError):append_market(old,fresh)

    def test_missing_dates_and_benchmark_correction_rejected(self):
        old=market(2)
        with self.assertRaises(ValueError):append_market(old,market(1))
        fresh=market(3);fresh['benchmark']['2026-01-02']+=1
        with self.assertRaises(ValueError):append_market(old,fresh)
        with self.assertRaises(ValueError):check_append({'a':'hash'}, {'a':'different'})


if __name__=='__main__':unittest.main()
