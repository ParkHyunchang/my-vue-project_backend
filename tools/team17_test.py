import copy
import unittest
from team17_validate import C, coverage, validate, et_date, stamp


def fixture():
    """Contract test only; deliberately no trades, prices or performance claims."""
    settings={k:1 for k in C['settings_required']}
    settings.update(signalMode='TREND',fundamentalFilterEnabled=True)
    deposit={k:0 for k in ['d0_usd_fx_entr','krw_ord_set_amt','won_dfr_amt','won_etc_loana']}
    deposit.update({'d'+str(day)+'_usd_'+suffix:0 for day in range(1,5) for suffix in ('fx_entr','sell_excta','buy_excta')})
    deposit['d0_usd_fx_entr']=10000
    return {'schema_version':1,'dataset_kind':'synthetic','initialization':'NEW_POLICY_NO_PRIOR_AUTOMATION','settings':settings,'initial_risk_equity_usd':10000,
            'frames':[{'id':'SYNTHETIC-1','at':'2026-11-17T15:00:00Z','observed_at':'2026-11-17T15:00:00Z','actions':['DECIDE'],
            'account':{'deposit':deposit,'balance':{'crnc_code':'USD','result_list':[]}},'rankings':[],'quotes':{},'fundamentals':{},'universe':{},
            'completed_bars':{},'broker':{'open_orders':{'result_list':[]},'fills':{'result_list':[]},'history':{'result_list':[]},'orderability':{},'write_responses':[]}}]}


class AdmissionTests(unittest.TestCase):
    def test_synthetic_structural_pass_never_becomes_performance(self):
        result=validate(fixture())
        self.assertEqual(result['status'],'STRUCTURE_VALID_SYNTHETIC')
        self.assertFalse(result['performance_allowed'])
        self.assertFalse(result['returns_computed'])

    def test_relabeling_synthetic_as_real_cannot_pass_pit_gate(self):
        data=fixture(); data['dataset_kind']='real_point_in_time'
        result=validate(data)
        self.assertEqual(result['status'],'BLOCKED_INCOMPLETE_PIT_METADATA')
        self.assertFalse(result['performance_allowed'])

    def test_market_data_coverage_explicitly_blocks_performance(self):
        result=coverage()
        self.assertFalse(result['performance_allowed'])
        self.assertIn('BLOCKED',result['status'])
        self.assertEqual(result['requirements']['ranking_pagination_complete'],'MISSING')

    def test_account_future_and_stale_rejected(self):
        for when in ('2026-11-17T15:00:01Z','2026-11-17T14:58:29Z'):
            data=fixture(); data['frames'][0]['observed_at']=when
            self.assertTrue(validate(data)['core_errors'])

    def test_et_calendar_prevents_future_daily_bar(self):
        data=fixture(); frame=data['frames'][0]
        frame['at']=frame['observed_at']='2026-11-18T01:00:00Z' # Still Nov17 in New York.
        frame['completed_bars']['SPY']=[{'date':'2026-11-17','open':1,'high':1,'low':1,'close':1,'volume':1}]
        self.assertIn('current/future ET session bar forbidden',validate(data)['core_errors'])

    def test_duplicate_frames_and_missing_settings_rejected(self):
        data=fixture(); data['frames'].append(copy.deepcopy(data['frames'][0])); del data['settings']['maxPositions']
        result=validate(data)
        self.assertTrue(any('maxPositions' in x for x in result['core_errors']))
        self.assertIn('duplicate frame ids',result['core_errors'])

    def test_unknown_and_fabricated_fundamental_status_rejected(self):
        for status in ('ALWAYS_PASS','CACHE_FRESH'):
            data=fixture(); data['frames'][0]['fundamentals']['X']={'available_at':'2026-11-17T15:00:00Z','lookup_status':status,'forwardPe':None,'trailingPe':None,'roePercent':None}
            self.assertTrue(validate(data)['core_errors'])

    def test_observed_unavailable_fundamental_is_explicit_synthetic_case(self):
        data=fixture(); data['frames'][0]['fundamentals']['X']={'available_at':'2026-11-17T15:00:00Z','lookup_status':'DATA_UNAVAILABLE','forwardPe':None,'trailingPe':None,'roePercent':None}
        self.assertEqual(validate(data)['core_errors'],[])

    def test_tokens_and_nonfinite_rejected(self):
        data=fixture(); data['access_token']='DO_NOT_PRINT'; data['initial_risk_equity_usd']=float('nan')
        result=validate(data)
        self.assertTrue(result['core_errors'])
        self.assertNotIn('DO_NOT_PRINT',str(result))

    def test_missing_actual_service_and_benchmark_data_cannot_be_asserted_true(self):
        data=fixture(); data['dataset_kind']='real_point_in_time'; data['metadata']={'point_in_time':{k:True for k in C['pit_required']},'replay_evidence':{'validated':True}}
        result=validate(data)
        self.assertFalse(result['performance_allowed'])
        self.assertTrue(any('evidence artifact' in x for x in result['performance_blockers']))

    def test_prior_automation_and_initial_orders_rejected(self):
        data=fixture(); data['initial_state']={}; data['frames'][0]['broker']['open_orders']['result_list']=[{}]
        self.assertTrue(validate(data)['core_errors'])

    def test_unknown_settings_and_missing_broker_response_rejected(self):
        data=fixture(); data['settings']['unknown']=1; data['frames'][0]['broker']['write_responses']=[{'api_id':'ust20000','response':{}}]
        errors=validate(data)['core_errors']
        self.assertIn('unknown settings key',errors)
        self.assertTrue(any('return_code' in x for x in errors))

    def test_missing_raw_cash_and_invalid_setting_type_rejected(self):
        data=fixture(); del data['frames'][0]['account']['deposit']['d4_usd_fx_entr']; data['settings']['maxPositions']='3'
        errors=validate(data)['core_errors']
        self.assertTrue(any('d4_usd_fx_entr' in x for x in errors))
        self.assertTrue(any('maxPositions' in x for x in errors))

    def test_et_date_accounts_for_summer_and_winter_offsets(self):
        self.assertEqual(str(et_date(stamp('2026-07-01T04:30:00Z'))),'2026-07-01')
        self.assertEqual(str(et_date(stamp('2026-12-01T04:30:00Z'))),'2026-11-30')


if __name__=='__main__': unittest.main()
