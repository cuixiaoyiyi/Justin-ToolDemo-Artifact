"""Pure JSON accounting copied from 1020; no Java/tests/generation/requests.

341 additions preserve the original request-window accounting and explicitly
separate setup/preflight identities, as well as observed total-token fields.
"""
from collections import Counter, defaultdict
import json
from pathlib import Path
import math

def read_jsonl(path):
    path=Path(path)
    if not path.exists():return [],[]
    raw=path.read_bytes();lines=raw.splitlines();rows=[];issues=[]
    for n,line in enumerate(lines,1):
        if not line.strip():continue
        try:
            row=json.loads(line)
            if not isinstance(row,dict):raise ValueError('not an object')
            rows.append(row)
        except (ValueError,TypeError) as exc:
            issues.append(dict(path=str(path),line=n,error=type(exc).__name__,
                               live_partial_tail=n==len(lines) and not raw.endswith(b'\n')))
    return rows,issues

def identity(r):
    explicit=(r['tool'],r['method_id']) if r.get('tool') and r.get('method_id') else None
    parts=r.get('label','').strip('/').split('/')
    label=tuple(parts) if len(parts)==2 else None
    if explicit and label and explicit!=label:return None,'label_identity_conflict'
    return (explicit or label), None if explicit or label else 'missing_identity'

def key(r):
    if r.get('attempt_id'):return r.get('proxy_instance'),r['attempt_id']
    if r.get('request_id') and r.get('attempt') is not None:
        return r.get('proxy_instance'),r['request_id'],r['attempt']
    return None

def count(x):
    if isinstance(x,bool):return None
    if isinstance(x,int) and x>=0:return x
    if isinstance(x,str) and x.isdigit():return int(x)
    return None

def first(*values):
    for value in values:
        got=count(value)
        if got is not None:return got
    return None

def usage_values(r):
    u=r.get('usage');u=u if isinstance(u,dict) else {}
    details=u.get('prompt_tokens_details');details=details if isinstance(details,dict) else {}
    inp=first(u.get('prompt_tokens'),u.get('input_tokens'),r.get('prompt_tokens'))
    out=first(u.get('completion_tokens'),u.get('output_tokens'),r.get('completion_tokens'))
    cached=first(u.get('prompt_cache_hit_tokens'),details.get('cached_tokens'),
                 u.get('cache_read_input_tokens'),r.get('cached_tokens'))
    miss=first(u.get('prompt_cache_miss_tokens'))
    if inp is None and cached is not None and miss is not None:inp=cached+miss
    total=first(u.get('total_tokens'),r.get('total_tokens'))
    if total is None and inp is not None and out is not None:total=inp+out
    return dict(input=inp,output=out,cached=cached,cache_miss=miss,total=total)

def index_usage(usage,lifecycle,valid_pairs):
    by=defaultdict(list);pending=defaultdict(list);unique={};started={}
    duplicates=[];issues=[];orphans=[];deliveries=[]
    for r in usage:
        if r.get('event') not in (None,'attempt_finished'):
            issues.append(dict(kind='unexpected_usage_event',event=r.get('event')));continue
        pair,error=identity(r)
        if error or pair not in valid_pairs:
            orphans.append(dict(reason=error or 'unknown_frozen_pair',record=r));continue
        k=key(r)
        if k is None:
            issues.append(dict(kind='finished_attempt_without_id',tool=pair[0],method_id=pair[1]))
            k=('missing-id',len(unique))
        if k in unique:
            same=unique[k]==r;duplicates.append(dict(attempt_key=k,identical=same))
            if not same:issues.append(dict(kind='conflicting_duplicate_attempt',attempt_key=k))
            continue
        unique[k]=r;by[pair].append(r)
        v=usage_values(r)
        if v['cached'] is not None and v['input'] is not None and v['cached']>v['input']:
            issues.append(dict(kind='cached_exceeds_input',attempt_key=k))
        if all(v[x] is not None for x in ['input','cached','cache_miss']) and v['cached']+v['cache_miss']!=v['input']:
            issues.append(dict(kind='cache_hit_miss_total_mismatch',attempt_key=k))
        if all(v[x] is not None for x in ['input','output','total']) and v['input']+v['output']!=v['total']:
            issues.append(dict(kind='input_output_total_mismatch',attempt_key=k))
    for r in lifecycle:
        if r.get('event')=='attempt_started':
            pair,error=identity(r);k=key(r)
            if error or pair not in valid_pairs or k is None:
                issues.append(dict(kind='invalid_started_identity',record=r));continue
            started[k]=r
        elif r.get('event')=='client_delivery':deliveries.append(r)
        elif r.get('event')=='handler_internal_error':issues.append(dict(kind='proxy_handler_internal_error',record=r))
    for k,r in started.items():
        if k not in unique:
            pair,_=identity(r);pending[pair].append(r)
    absent=[k for k in unique if k not in started]
    if absent:issues.append(dict(kind='finished_without_lifecycle_start',attempt_keys=absent))
    audit=dict(unique_finished_attempts=len(unique),started_attempts=len(started),
        duplicate_finished_records=duplicates,unmatched_usage_records=orphans,issues=issues,
        pending_attempts=[r for rr in pending.values() for r in rr],client_delivery_records=len(deliveries),
        cancelled_deliveries=sum(r.get('delivery')=='client_cancelled_or_disconnected' for r in deliveries))
    return by,pending,audit

def cost_summary(calls,pending=()):
    vv=[usage_values(r) for r in calls]
    unknown=sum(v['input'] is None or v['output'] is None for v in vv)
    result=dict(api_attempts=len(calls),
        client_requests=len({r['request_id'] for r in calls if r.get('request_id')}),
        proxy_retry_attempts=sum((count(r.get('attempt')) or 0)>1 for r in calls),
        api_status_counts=dict(Counter(str(r.get('status')) for r in calls)),
        attempts_without_usage=unknown,pending_attempts=len(pending),
        input_unknown_attempts=sum(v['input'] is None for v in vv),
        output_unknown_attempts=sum(v['output'] is None for v in vv),
        total_unknown_attempts=sum(v['total'] is None for v in vv),
        cache_usage_unknown_attempts=sum(v['cached'] is None for v in vv),
        usage_complete=unknown==0 and not pending,
        token_totals_are_observed_lower_bounds=unknown>0 or bool(pending),
        cached_tokens_included_in_input=True)
    for metric in ['input','output','cached','cache_miss','total']:
        result[metric+'_tokens']=sum(v[metric] or 0 for v in vv)
    result['total_tokens_are_observed_lower_bounds']=result['total_unknown_attempts']>0 or bool(pending)
    result['cached_tokens_are_observed_lower_bounds']=result['cache_usage_unknown_attempts']>0 or bool(pending)
    result['upstream_attempt_seconds']=sum(r.get('seconds',0) for r in calls if isinstance(r.get('seconds'),(int,float)) and not isinstance(r.get('seconds'),bool))
    return result

def partition_generation_windows(by,pending,generations,excluded_runs=()):
    """Separate current valid generation costs from retained setup/invalid costs.

    Every retry keeps the proxy's original received_unix; completion after a
    generation timeout does not remove its already-incurred upstream cost.
    """
    primary=defaultdict(list);primary_pending=defaultdict(list)
    overhead=[];overhead_pending=[];awaiting=[];awaiting_pending=[];issues=[]
    for pair in set(by)|set(pending)|set(generations):
        generation=generations.get(pair)
        for waiting,source in [(False,by.get(pair,[])),(True,pending.get(pair,[]))]:
            for r in source:
                if generation is None:
                    (awaiting_pending if waiting else awaiting).append(r)
                    continue
                received=r.get('received_unix')
                lo,hi=generation.get('started_unix'),generation.get('finished_unix')
                valid=all(isinstance(x,(int,float)) and not isinstance(x,bool) and math.isfinite(x) for x in [received,lo,hi])
                if not valid or lo>hi:
                    issues.append(dict(kind='invalid_generation_accounting_window',tool=pair[0],method_id=pair[1],attempt_id=r.get('attempt_id')))
                    (awaiting_pending if waiting else awaiting).append(r)
                elif lo<=received<=hi:
                    (primary_pending if waiting else primary)[pair].append(r)
                else:
                    item=dict(r,accounting_class='outside_current_generation_window')
                    matched=[x for x in excluded_runs if x.get('tool')==pair[0] and x.get('method_id')==pair[1]
                             and x.get('started_unix',float('inf'))<=received<=x.get('finished_unix',float('-inf'))]
                    if matched:
                        item['excluded_generation_evidence']=matched
                        item['accounting_class']='retained_preflight_invalid_generation'
                    (overhead_pending if waiting else overhead).append(item)
    audit=dict(policy='Current done.started_unix <= original request received_unix <= done.finished_unix; all retries of that request retained.',
        primary_finished_attempts=sum(len(x) for x in primary.values()),
        primary_pending_attempts=sum(len(x) for x in primary_pending.values()),
        setup_invalid_overhead=cost_summary(overhead,overhead_pending),
        overhead_records=overhead,overhead_pending_records=overhead_pending,
        awaiting_generation_window=cost_summary(awaiting,awaiting_pending),
        awaiting_generation_records=awaiting,awaiting_pending_records=awaiting_pending,issues=issues)
    return primary,primary_pending,audit


def split_auxiliary_records(usage,lifecycle,valid_tools,prefixes=('setup-','preflight-')):
    """Partition recognized auxiliary identities before frozen-pair auditing.

    Malformed/conflicting identities are left in the main audit, never hidden by
    a prefix appearing in one untrusted field. Returned auxiliary pairs are
    audited through index_usage just like the real method/tool pairs.
    """
    tools=set(valid_tools);main_usage=[];main_lifecycle=[];aux_usage=[];aux_lifecycle=[];pairs=set()
    for source,main,aux in [(usage,main_usage,aux_usage),(lifecycle,main_lifecycle,aux_lifecycle)]:
        for record in source:
            pair,error=identity(record)
            if not error and pair[0] in tools and pair[1].startswith(prefixes):
                aux.append(record);pairs.add(pair)
            else:main.append(record)
    return main_usage,main_lifecycle,aux_usage,aux_lifecycle,pairs


def whole_ledger_cost(usage,lifecycle):
    """Retain even unattributed upstream costs, deduplicated by attempt id.

    Conflicting identity/duplicate records remain audit errors elsewhere. The
    first finished record is counted once; missing attempt ids use individual
    records because deduplication cannot be proved.
    """
    finished={};started={}
    for n,record in enumerate(usage):
        if record.get('event') not in (None,'attempt_finished'):continue
        ident=key(record) or ('unidentified-finished-record',n)
        finished.setdefault(ident,record)
    for n,record in enumerate(lifecycle):
        if record.get('event')!='attempt_started':continue
        ident=key(record) or ('unidentified-started-record',n)
        started.setdefault(ident,record)
    waiting=[record for ident,record in started.items() if ident not in finished]
    return cost_summary(list(finished.values()),waiting)
