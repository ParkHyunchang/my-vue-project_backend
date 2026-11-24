"""Offline actual-service replay admission. No account access or return calculation."""
import argparse
import hashlib
import json
import math
import re
from datetime import datetime, date, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

ROOT=Path(__file__).resolve().parent
C=json.loads((ROOT/'team17_contract.json').read_text(encoding='utf-8'))
SHA=re.compile('[0-9a-f]{64}')
FORBIDDEN={'access_token','refresh_token','appkey','secretkey','account_number','authorization','cookie'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stamp(value):
    value=datetime.fromisoformat(value.replace('Z','+00:00'))
    if value.tzinfo is None:
        raise ValueError('timezone required')
    return value


def number(value):
    return type(value) in (int,float) and math.isfinite(value)


def et_date(at):
    try:
        return at.astimezone(ZoneInfo('America/New_York')).date()
    except ZoneInfoNotFoundError:
        # Windows stdlib may have no tzdata. Bound this documented fallback to
        # the production calendar's 2026-2028 support; never guess older rules.
        utc=at.astimezone(timezone.utc)
        if not 2026<=utc.year<=2028: raise ValueError('ET timezone data unavailable outside supported 2026-2028')
        march=date(utc.year,3,1); november=date(utc.year,11,1)
        start=datetime(utc.year,3,1+(6-march.weekday())%7+7,7,tzinfo=timezone.utc)
        end=datetime(utc.year,11,1+(6-november.weekday())%7,6,tzinfo=timezone.utc)
        return (utc+timedelta(hours=-4 if start<=utc<end else -5)).date()


def validate(data):
    errors=[]
    def require(obj,keys,path):
        if not isinstance(obj,dict):
            errors.append(path+': object required'); return False
        missing=[key for key in keys if key not in obj]
        errors.extend(path+'.'+key+': required' for key in missing)
        return not missing
    def time(value,path):
        try: return stamp(value)
        except (ValueError,TypeError,AttributeError):
            errors.append(path+': timezone timestamp required'); return None
    def clean(obj,path='input'):
        if isinstance(obj,dict):
            for key,value in obj.items():
                if key.lower() in FORBIDDEN: errors.append(path+'.'+key+': credential forbidden')
                clean(value,path+'.'+key)
        elif isinstance(obj,list):
            for item in obj: clean(item,path+'[]')
        elif isinstance(obj,float) and not math.isfinite(obj): errors.append(path+': nonfinite')
    clean(data)
    kind=data.get('dataset_kind') if isinstance(data,dict) else None
    if not require(data,C['top_required'],'input'): return outcome(errors,[],kind)
    if data['schema_version']!=1 or kind not in C['dataset_kinds']: errors.append('unsupported schema/kind')
    if data['initialization']!='NEW_POLICY_NO_PRIOR_AUTOMATION': errors.append('unsupported initial state: only new policy supported')
    if any(k in data for k in ('initial_state','initial_proposals','initial_risk','initial_control')): errors.append('restoring prior automation state is unsupported')
    require(data['settings'],C['settings_required'],'settings')
    if isinstance(data['settings'],dict) and set(data['settings'])-set(C['settings_required']): errors.append('unknown settings key')
    if isinstance(data['settings'],dict):
        for key,value in data['settings'].items():
            if key=='signalMode':
                if value not in ('LEGACY','OBSERVE','TREND'): errors.append('invalid signalMode')
            elif key=='fundamentalFilterEnabled':
                if type(value) is not bool: errors.append('boolean fundamentalFilterEnabled required')
            elif not number(value) or value<0: errors.append('nonnegative numeric setting required: '+key)
    if not number(data['initial_risk_equity_usd']) or data['initial_risk_equity_usd']<=0: errors.append('initial equity must be positive')
    frames=data['frames']
    if not isinstance(frames,list) or not frames: return outcome(errors+['frames must be nonempty array'],[],kind)
    ids=[]; previous=None
    for frame in frames:
        if not require(frame,C['frame_required'],'frame'): continue
        ids.append(frame['id'])
        at=time(frame['at'],'frame.at'); observed=time(frame['observed_at'],'frame.observed_at')
        if not at: continue
        if previous and at<=previous: errors.append('frame times must increase')
        previous=at
        try: session=et_date(at)
        except ValueError as error: errors.append(str(error)); continue
        if observed and not 0<=(at-observed).total_seconds()<=90: errors.append('account observed_at stale/future')
        if not isinstance(frame['actions'],list) or not frame['actions'] or any(x not in ('DECIDE','MONITOR','RECONCILE') for x in frame['actions']): errors.append('invalid actions')
        if require(frame['account'],['deposit','balance'],'account'):
            account=frame['account']
            cash_fields=['d0_usd_fx_entr','krw_ord_set_amt','won_dfr_amt','won_etc_loana']
            cash_fields.extend('d'+str(day)+'_usd_'+suffix for day in range(1,5) for suffix in ('fx_entr','sell_excta','buy_excta'))
            require(account['deposit'],cash_fields,'account.deposit')
            if require(account['balance'],['crnc_code','result_list'],'account.balance'):
                holdings=account['balance']['result_list']
                if not isinstance(holdings,list): errors.append('balance rows required')
                else:
                    for holding in holdings:
                        if require(holding,['stk_cd','stex_cd','crnc_code','poss_qty','sell_alowq','avg_pric','now_pric','pl_rt'],'holding'):
                            try:
                                if int(holding['poss_qty'])>0 and holding['stk_cd'] not in frame['quotes']: errors.append('holding contemporaneous quote required')
                            except (ValueError,TypeError): errors.append('invalid holding quantity')
        require(frame['broker'],C['broker_required'],'broker')
        if isinstance(frame['broker'],dict):
            for key in ('open_orders','fills','history'):
                value=frame['broker'].get(key,{})
                if not isinstance(value,dict) or not isinstance(value.get('result_list'),list): errors.append('broker.'+key+': result_list required')
                elif frame is frames[0] and value['result_list']: errors.append('first frame prior broker orders/fills unsupported')
        rankings=frame['rankings']
        if not isinstance(rankings,list): errors.append('rankings array required'); continue
        if len(rankings)>50: errors.append('rankings exceed top50')
        symbols=set(); ranks=[]
        for row in rankings:
            if require(row,C['ranking_required'],'ranking'):
                symbols.add(row['symbol']); ranks.append(row['rank'])
                if not all(number(row[k]) and row[k]>=0 for k in ('accumulatedVolume','previousVolume','tradedValue')): errors.append('invalid rank volume/value')
        if len(symbols)!=len(rankings) or ranks!=sorted(set(ranks)): errors.append('duplicate/reverse rankings')
        for name in ('quotes','fundamentals','universe','completed_bars'):
            group=frame[name]
            if not isinstance(group,dict): errors.append(name+': symbol map required'); continue
            required=symbols|{'SPY','QQQ'} if name=='completed_bars' and symbols else symbols
            if not required<=set(group): errors.append(name+': missing ranked/market symbol')
            for symbol,item in group.items():
                if name=='completed_bars':
                    if not isinstance(item,list): errors.append('bar list required'); continue
                    if len(item)<51: errors.append('at least51 completed bars required')
                    days=[]
                    for bar in item:
                        if not require(bar,['date','open','high','low','close','volume'],'bar'): continue
                        try:
                            day=date.fromisoformat(bar['date']); days.append(day)
                            if day>=session: errors.append('current/future ET session bar forbidden')
                        except (TypeError,ValueError): errors.append('invalid bar date')
                        if not all(number(bar[k]) and bar[k]>0 for k in ('open','high','low','close')): errors.append('invalid bar prices')
                        elif not bar['low']<=min(bar['open'],bar['close'])<=max(bar['open'],bar['close'])<=bar['high']: errors.append('invalid OHLC')
                        if not number(bar['volume']) or bar['volume']<=0: errors.append('invalid bar volume')
                    if days!=sorted(set(days)): errors.append('duplicate/reverse bars')
                    continue
                fields={'quotes':['bid','ask','available_at'],'fundamentals':['available_at','lookup_status'],'universe':['eligible','label','available_at']}[name]
                if not require(item,fields,name): continue
                available=time(item['available_at'],name+'.available_at')
                if available and (available>at or (name=='quotes' and (at-available).total_seconds()>90)): errors.append(name+': stale/future input')
                if name=='quotes' and (not all(number(item[k]) and item[k]>0 for k in ('bid','ask')) or item['ask']<item['bid']): errors.append('invalid bid/ask')
                if name=='fundamentals':
                    if item['lookup_status'] not in C['fundamental_statuses']: errors.append('invalid lookup status')
                    valid=all(number(item.get(k)) for k in ('forwardPe','trailingPe','roePercent'))
                    if not valid and item['lookup_status'] in ('CACHE_FRESH','YAHOO_REFRESHED','CACHE_STALE_FALLBACK'): errors.append('available fundamental status has no numeric values')
                if name=='universe' and type(item['eligible']) is not bool: errors.append('universe boolean required')
        for response in frame['broker'].get('write_responses',[]):
            if require(response,['api_id','response'],'write_response'):
                if response['api_id'] not in ('ust20000','ust20001','ust20003'): errors.append('unsupported write API')
                require(response['response'],['return_code'],'write_response.response')
        if not symbols<=set(frame['broker'].get('orderability',{})): errors.append('ranked symbol orderability missing')
    if len(ids)!=len(set(ids)): errors.append('duplicate frame ids')
    blockers=performance_metadata(data.get('metadata'),ids,kind)
    return outcome(errors,blockers,kind)


def performance_metadata(meta,frame_ids,kind):
    if not isinstance(meta,dict): return ['PIT provenance/accounting/actual-decision metadata missing']
    blockers=['metadata.'+k+': required' for k in C['metadata_required'] if k not in meta]
    if meta.get('expected_frame_ids')!=frame_ids: blockers.append('expected event schedule mismatch')
    if meta.get('gaps')!=[]: blockers.append('gap audit missing or unresolved gaps')
    sources=meta.get('sources',[])
    if not sources: blockers.append('PIT source inventory absent')
    source_ids={x.get('id') for x in sources if isinstance(x,dict)}
    for source in sources:
        if not isinstance(source,dict) or not all(k in source for k in ('id','kind','sha256','archive_uri','captured_at')): blockers.append('incomplete source provenance')
        elif kind=='real_point_in_time' and source['kind']!='real_point_in_time': blockers.append('synthetic/retrospective source is not PIT')
    for key in C['pit_required']:
        proof=meta.get('point_in_time',{}).get(key)
        if not isinstance(proof,dict) or not SHA.fullmatch(str(proof.get('sha256',''))) or not proof.get('source_ids') or not set(proof['source_ids'])<=source_ids: blockers.append(key+': evidence artifact required, not a boolean')
    for key in C['initial_state_required']:
        if key not in meta.get('initial_state',{}): blockers.append('initial_state.'+key+': required')
    replay=meta.get('replay_evidence',{})
    if replay.get('actual_service_class')!='com.hyunchang.webapp.service.KiwoomUsAutoTradeService': blockers.append('actual service decision artifact required')
    for field in ('input_sha256','decision_artifact_sha256','ledger_audit_sha256'):
        if not SHA.fullmatch(str(replay.get(field,''))): blockers.append(field+': digest required')
    if not replay.get('source_sha256') or not replay.get('decision_ids'): blockers.append('exact service hashes and decisions required')
    if kind=='real_point_in_time' and replay.get('synthetic') is not False: blockers.append('synthetic decisions cannot validate historical performance')
    benchmark=meta.get('benchmark',{})
    if benchmark.get('kind')!='SP500_TOTAL_RETURN': blockers.append('same-time SP500 total return required')
    points={x.get('at'):x.get('value') for x in benchmark.get('points',[])}
    evaluations=meta.get('evaluations',[])
    if len(evaluations)<2: blockers.append('baseline and subsequent equity valuation required')
    previous=None
    for item in evaluations:
        if not isinstance(item,dict) or any(k not in item for k in C['evaluation_required']): blockers.append('incomplete valuation accounting'); continue
        try:
            at=stamp(item['at'])
            if previous and at<=previous: blockers.append('duplicate/reverse valuation time')
            previous=at
        except (ValueError,TypeError,AttributeError): blockers.append('invalid valuation time')
        if not number(points.get(item['at'])) or points[item['at']]<=0: blockers.append('benchmark timestamp mismatch/missing level')
        if item['flow_timing']!='PERIOD_END_INCLUDED_IN_EQUITY': blockers.append('intraperiod flows require valuation splitting')
        if item['valuation_complete'] is not True: blockers.append('incomplete valuation')
        if not all(number(item[k]) for k in ('equity_usd','external_flow_usd','fees_usd','dividend_usd')) or item['equity_usd']<=0: blockers.append('invalid equity/flow/cost/dividend')
        if not set(item['decision_ids'])<=set(replay.get('decision_ids',[])): blockers.append('decision ids not covered by service output')
    return blockers


def outcome(errors,blockers,kind):
    status='BLOCKED_INVALID_CORE' if errors else 'STRUCTURE_VALID_SYNTHETIC' if kind=='synthetic' else 'BLOCKED_INCOMPLETE_PIT_METADATA' if blockers else 'STRUCTURE_VALID_PROVENANCE_UNVERIFIED'
    return {'status':status,'dataset_kind':kind,'core_errors':errors,'performance_blockers':blockers,'performance_allowed':False,'returns_computed':False,
            'reason':'Structure validation does not authenticate PIT archives or execute/audit actual-service decisions. Synthetic data never proves performance.'}


def coverage():
    inventory=[]
    for folder in ('market_data','strategy_v2_data','strategy_v3_data'):
        path=ROOT/folder/'manifest.json'
        if not path.exists(): continue
        manifest=json.loads(path.read_text(encoding='utf-8')); files=[]
        for item in manifest.get('files',[]):
            checked={}
            for kind in ('raw','csv'):
                name=item.get(kind+'_file')
                if name:
                    file=ROOT/folder/name
                    checked[kind+'_hash_matches']=file.exists() and digest(file)==item.get(kind+'_sha256')
            files.append({'symbol':item.get('symbol'),'rows':item.get('rows'),'first_date':item.get('first_date'),'last_date':item.get('last_date'),**checked})
        inventory.append({'folder':folder,'manifest_sha256':digest(path),'kind':'RETROSPECTIVE_DAILY_ONLY','files':files})
    required={key:'MISSING' for key in C['pit_required']}
    required.update(completed_daily_ohlcv='PARTIAL: retrospective, not archived as-known-at-decision',benchmark_total_return='PARTIAL: daily levels exist, actual-service equity timeline absent',dividends='PARTIAL: ex-date events, payment/account ledger absent',initial_existing_holdings_cash_pending_risk='MISSING',external_cashflows='MISSING',actual_service_decision_and_ledger_audit='MISSING',expected_event_schedule='MISSING')
    return {'status':'BLOCKED_INCOMPLETE_POINT_IN_TIME_DATA','performance_allowed':False,'returns_computed':False,
            'claim':'현재 일봉자료로 실제 자동매매 서비스 전체의 S&P500 대비 수익률을 검증할 수 없음',
            'inventory':inventory,'requirements':required,'no_values_fabricated':True,'network_or_account_access':False,
            'synthetic_purpose':'actual-service logic regression only, never historical profitability'}


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('input',nargs='?'); parser.add_argument('--coverage',action='store_true'); parser.add_argument('--output'); args=parser.parse_args()
    try:
        if args.coverage: report=coverage()
        elif args.input:
            raw=Path(args.input).read_bytes()
            report=validate(json.loads(raw.decode('utf-8-sig')))
            report['input_sha256']=hashlib.sha256(raw).hexdigest()
            report['validator_sha256']=digest(Path(__file__))
            report['contract_sha256']=digest(ROOT/'team17_contract.json')
        else: parser.error('input or --coverage required')
    except (ValueError,TypeError,KeyError,AttributeError,OSError) as error:
        report={'status':'BLOCKED_INVALID_CORE','performance_allowed':False,'returns_computed':False,'error':str(error)}
    text=json.dumps(report,ensure_ascii=False,indent=2,allow_nan=False)+'\n'
    if args.output: Path(args.output).write_text(text,encoding='utf-8')
    print(text)
    return 2 if report['status'].startswith('BLOCKED') else 0


if __name__=='__main__': raise SystemExit(main())
