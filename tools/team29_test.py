import copy
import unittest
from datetime import datetime,timezone,timedelta
import team29_research as r

def sessions(crash=False):
 out={}
 for d in range(3):
  day=f'2026-11-{16+d:02}';stamp=int(datetime(2026,11,16+d,14,30,tzinfo=timezone.utc).timestamp());bars={}
  for s in r.SYMBOLS:
   rows=[]
   for i in range(78):
    p=100 if i<6 else 102 if s=='AAPL' else 100
    if crash and d==0 and i>=8 and s=='AAPL':p=70
    rows.append(dict(date=day,symbol=s,slot=i,timestamp_utc=stamp+i*300,open=p,high=p+.1,low=p-.1,close=p,volume=1000))
   bars[s]=rows
  out[day]=bars
 return out

class Tests(unittest.TestCase):
 def test_no_same_bar_and_eod_open(self):
  result=r.intraday_run(sessions(),'orb30',25)
  self.assertEqual(len(result['trades']),6)
  for t in result['trades']:
   self.assertGreaterEqual(t['timestamp_utc'],t['signal_available_timestamp']) if t['signal_timestamp'] else None
   if t['side']=='SELL':self.assertEqual(t['slot'],77)
  self.assertEqual(result['history'][-1]['qty'],0)

 def test_cash_settlement_and_one_buy(self):
  result=r.intraday_run(sessions(),'orb30',25)
  for day in sessions():
   trades=[t for t in result['trades'] if t['date']==day]
   self.assertEqual(sum(t['side']=='BUY' for t in trades),1)
   self.assertEqual(trades[0]['cash_after'],trades[-1]['cash_after'])
  self.assertGreater(result['history'][78]['settled_today'],0)
  self.assertTrue(all(h['cash']>=0 for h in result['history']))

 def test_low_latch_and_no_restart(self):
  result=r.intraday_run(sessions(True),'orb30',10)
  self.assertTrue(result['hard_latch'])
  self.assertEqual(sum(t['side']=='BUY' for t in result['trades']),1)
  self.assertTrue(result['history'][-1]['hard'])

 def test_signal_prefix_invariance_cutoff(self):
  bars=sessions()['2026-11-16'];changed=copy.deepcopy(bars)
  for s in changed:
   for b in changed[s][20:]:b['close']=9999
  self.assertEqual(r.entry_signal(bars,10,'orb30'),r.entry_signal(changed,10,'orb30'))
  self.assertIsNone(r.entry_signal(bars,75,'orb30'))
  self.assertIsNone(r.entry_signal(bars,5,'orb30'))

 def test_daily_actual_age_and_tie(self):
  qty={s:0 for s in r.SYMBOLS};ages=dict(qty);cl={s:99 for s in qty}
  sig={s:dict(eligible=True,momentum63=.1,rsi2=5,sma5=101,sma200=90) for s in qty}
  qty['AAPL']=2;ages['AAPL']=5
  result=r.daily_select('stock_pullback5',[],qty,ages,sig,cl,False)
  self.assertNotIn('AAPL',result);self.assertEqual(len(result),3)
  self.assertEqual(r.daily_select('stock_rank_monthly',[],qty,ages,sig,cl,True),sorted(r.DAILY_STOCKS)[:3])

 def test_private_engine_source_unchanged(self):
  import team23_research as old
  before=old.SYMBOLS
  m=r.private_engine();self.assertNotEqual(m.SYMBOLS,old.SYMBOLS)
  self.assertEqual(old.SYMBOLS,before)

if __name__=='__main__':unittest.main()
