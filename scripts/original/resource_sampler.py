"""Sample machine resources only; no model, generators, compilers or tests."""
from pathlib import Path
import fcntl
import json
import shutil
import time

ROOT=Path('/home/clm/tooldemo72-20261023')
def load(p):
    try:return json.loads(p.read_text())
    except (OSError,ValueError):return {}
def main():
    with (ROOT/'resource-sampler.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        previous=None;count=0;maxcpu=0.;minavail=None
        print('RESOURCE_SAMPLER_STARTED',str(ROOT/'resource-samples.jsonl'),flush=True)
        while True:
            cpu=list(map(int,Path('/proc/stat').read_text().splitlines()[0].split()[1:9]))
            total=sum(cpu);idle=cpu[3]+cpu[4]
            pct=None if previous is None else round(100*(1-(idle-previous[1])/max(1,total-previous[0])),3)
            previous=(total,idle)
            mem={line.split(':',1)[0]:int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines() if line.split()[1].isdigit()}
            state=load(ROOT/'state.json');assist=load(ROOT/'parallel-assist-state.json')
            used,usedtotal,free=shutil.disk_usage(ROOT)
            # Names only; never inspect or persist process environments/credentials.
            java=0
            for p in Path('/proc').glob('[0-9]*/comm'):
                try:java+=p.read_text().strip()=='java'
                except OSError:pass
            row=dict(unix=time.time(),cpu_busy_pct=pct,loadavg=Path('/proc/loadavg').read_text().split()[:3],
                mem_total_kib=mem['MemTotal'],mem_available_kib=mem['MemAvailable'],
                swap_used_kib=mem.get('SwapTotal',0)-mem.get('SwapFree',0),disk_free_bytes=free,
                java_processes=java,primary_status=state.get('status'),
                primary_resources=state.get('resources'),assist_status=assist.get('status'))
            with (ROOT/'resource-samples.jsonl').open('a') as f:f.write(json.dumps(row)+'\n')
            count+=1;maxcpu=max(maxcpu,pct or 0);minavail=min(minavail or mem['MemAvailable'],mem['MemAvailable'])
            if (ROOT/'stop-resource-sampler').exists() or state.get('status')=='complete':break
            time.sleep(15)
        summary=dict(status='complete',samples_this_process=count,max_cpu_busy_pct_this_process=maxcpu,
                     min_mem_available_kib_this_process=minavail,finished_unix=time.time(),
                     limitation='Resource samples accompany the 72-method batch; admission limits are recorded separately.')
        (ROOT/'resource-summary.json').write_text(json.dumps(summary,indent=2))
        print('RESOURCE_SAMPLER_FINISHED',json.dumps(summary),flush=True)
if __name__=='__main__':main()
