import copy
import unittest
import team30_research as r
import team24_research as original
from team24_test import fixture

class Tests(unittest.TestCase):
 def test_baseline_exact(self):
  data=fixture();start=data['SPY'][220]['date'];end=data['SPY'][-1]['date']
  expected=original.simulate(data,start,end,'balanced_trend',25,risk_policy='recovery_vol')
  actual=r.simulate(data,start,end,'baseline')
  for h in actual['history']:
   h.pop('filter_state')
   for s in h['signal_snapshot'].values():
    s.pop('sma200_lag20');s.pop('market_up')
  self.assertEqual({k:actual[k] for k in expected},expected)

 def snap(self):
  return {s:dict(eligible=True,market_up=True,vol20=.2) for s in r.engine_symbols()}

 def test_entry_gates_keep_existing_and_only_add_monthly(self):
  for variant in ('market_entry','vol_entry'):
   p=r.FilterPolicy(variant,r.engine());snap=self.snap()
   snap['SPY'].update(market_up=False,vol20=.3)
   self.assertEqual(p.select('balanced_trend',['GLD'],snap,True),['GLD'])
   snap['SPY'].update(market_up=True,vol20=.2)
   self.assertEqual(p.select('balanced_trend',['GLD'],snap,False),['GLD'])
   self.assertEqual(len(p.select('balanced_trend',['GLD'],snap,True)),4)

 def test_selection_and_order_ablation(self):
  snap=self.snap();snap['GLD']['eligible']=False
  p=r.FilterPolicy('monthly_exit',r.engine())
  self.assertEqual(p.select('balanced_trend',['GLD'],snap,False),['GLD'])
  self.assertEqual(p.select('balanced_trend',['GLD'],snap,True),['SPY','QQQ','IEF'])
  self.assertEqual(p.order(False,.3,.8,['GLD'],['GLD'],False),'REDUCE')
  p=r.FilterPolicy('daily_entry',r.engine())
  self.assertEqual(p.order(False,.8,.8,['SPY'],['SPY','GLD'],False),'FULL')
  self.assertEqual(p.order(False,.8,.8,['SPY'],['SPY','GLD'],True),'HOLD')
  self.assertEqual(len(r.FilterPolicy('remove_sma',r.engine()).select('balanced_trend',[],snap,False)),4)

 def test_pause_cooldown_hysteresis_and_final_hard(self):
  p=r.FilterPolicy('pause6',r.engine());snap=self.snap();basket=[]
  def step(value,hard=False):
   nonlocal basket
   basket=p.select('balanced_trend',basket,snap,True)
   gross=p.risk(value,100,hard,'recovery_vol',.2)
   return gross,p.order(False,gross,.8,[],basket,hard)
  gross,mode=step(93)
  self.assertEqual((gross,mode,p.paused,p.armed,p.pause_day),(0.,'FULL',True,False,0))
  for _ in range(19):self.assertEqual(step(93)[0],0.)
  self.assertGreater(step(93)[0],0.);self.assertFalse(p.paused);self.assertFalse(p.armed)
  self.assertGreater(step(93)[0],0.);self.assertFalse(p.paused)
  step(98);self.assertTrue(p.armed)
  self.assertEqual(step(93)[0],0.);self.assertTrue(p.paused)
  for _ in range(25):self.assertEqual(step(89,True),(0.,'HOLD'))

 def test_cap_and_cash_latch_variants(self):
  data=fixture(290)
  for rows in data.values():
   for i,row in enumerate(rows):
    if i>=241:
     for key in ('open','close','adj_close','trade_open','trade_close'):row[key]*=.6
  for v in r.RULES:
   out=r.simulate(data,data['SPY'][220]['date'],data['SPY'][-1]['date'],v)
   self.assertTrue(all(h['cash']>=-1e-7 for h in out['history']))
   if v=='cap40':self.assertLessEqual(out['max_target_gross'],.4)
   latched=[i for i,h in enumerate(out['history']) if h['hard']]
   if latched:self.assertTrue(all(h['hard'] for h in out['history'][latched[0]:]))
   self.assertTrue(all(t['signal_date']<t['date'] for t in out['trades'] if t['signal_date']))

 def test_prefix_no_future_and_original_module(self):
  data=fixture();changed=copy.deepcopy(data)
  for rows in changed.values():
   for row in rows[250:]:row['adj_close']*=2
  a,b=r.indicators(data),r.indicators(changed)
  for s in a:self.assertEqual(a[s][:250],b[s][:250])
  self.assertEqual(original.SYMBOLS,r.engine_symbols())
  self.assertEqual(original.select_basket.__module__,'team24_research')

if __name__=='__main__':unittest.main()
