import copy
import unittest
from team24_test import fixture
from team24_research import indicators, simulate
from research_lab.replay import new_account, advance


class ReplayTests(unittest.TestCase):
    def test_incremental_matches_frozen_engine_all_candidates(self):
        data=fixture(255); signals=indicators(data)
        for candidate in ('rotation1','riskrank2','balanced_trend'):
            for policy in ('cushion80','recovery_vol'):
                account=new_account(10000,candidate+':'+policy)
                for i in range(200,255):
                    account=advance(account,data,signals,i)
                old=simulate(data,data['SPY'][200]['date'],data['SPY'][-1]['date'],candidate,25,signals=signals,risk_policy=policy)
                self.assertEqual(account['history'],old['history'])
                self.assertEqual(account['trades'],old['trades'])
                self.assertEqual(account['peak'],old['history'][-1]['peak'])

    def test_switch_preserves_risk_and_cancels_when_latched(self):
        data=fixture(230);signals=indicators(data)
        account=new_account(10000,'balanced_trend:recovery_vol')
        for i in range(200,210): account=advance(account,data,signals,i)
        before=copy.deepcopy(account)
        account['switch_to']='rotation1:cushion80'
        out=advance(account,data,signals,210)
        self.assertEqual(out['strategy_id'],'rotation1:cushion80')
        self.assertEqual(out['trades'][:len(before['trades'])],before['trades'])
        self.assertGreaterEqual(out['peak'],before['peak'])
        self.assertEqual(out['history'][-1]['next_target']['mode'],'FULL')
        out['hard']=True; out['switch_to']='riskrank2:recovery_vol'
        locked=advance(out,data,signals,211)
        self.assertTrue(locked['hard'])
        self.assertEqual(locked['strategy_id'],out['strategy_id'])
        self.assertTrue(all(t['side']=='SELL' for t in locked['trades'][len(out['trades']):]))

    def test_duplicate_day_rejected_without_mutation(self):
        data=fixture(); signals=indicators(data)
        account=advance(new_account(10000,'rotation1:recovery_vol'),data,signals,200)
        before=copy.deepcopy(account)
        with self.assertRaises(ValueError): advance(account,data,signals,200)
        self.assertEqual(account,before)


if __name__=='__main__': unittest.main()
