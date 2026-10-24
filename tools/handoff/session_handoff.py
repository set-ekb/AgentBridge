#!/usr/bin/env python3
"""Portable checkpoint exchange for AgentBridge agents (no Android DB access)."""
import argparse
import json
import os
import re
import tempfile
from pathlib import Path

VERSION = 1
STATES = {'PLAN','ACT','VERIFY','WAIT_USER','WAIT_QUOTA','PAUSED','FAILED','COMPLETE','CANCELLED'}
SENSITIVE = re.compile(r'(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|authorization|cookie|bearer)')

def safe_text(value):
    if not isinstance(value,str) or len(value)>4000 or SENSITIVE.search(value):
        raise ValueError('Invalid text or possible secret')
    return value

def make(task_id,goal,next_step,state='PAUSED',verified=None,constraints=None):
    if not re.fullmatch(r'[A-Za-z0-9._-]{1,100}',task_id) or state not in STATES:
        raise ValueError('Invalid task id/state')
    return {'schema_version':VERSION,'task_id':task_id,'goal':safe_text(goal),
            'state':state,'task_revision':1,'step':0,'previous_provider':'external',
            'previous_model':'external','conditions':'\\n'.join(safe_text(v) for v in (constraints or [])),
            'plan_summary':safe_text(next_step),'question':'','active_package_at_checkpoint':'',
            'last_tool':'','last_action':'','observation':'\\n'.join(safe_text(v) for v in (verified or [])),
            'action_may_have_occurred':False,'resume_requires_reverification':True}

def save(path,record):
    p=Path(path)
    if p.is_symlink(): raise ValueError('Symlink destination rejected')
    p.parent.mkdir(parents=True,exist_ok=True)
    tmp=None
    try:
        with tempfile.NamedTemporaryFile('w',encoding='utf-8',dir=p.parent,prefix='.handoff-',delete=False) as f:
            tmp=f.name
            os.chmod(tmp,0o600)
            json.dump(record,f,ensure_ascii=False,indent=2)
            f.write('\n')
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp,p)
    finally:
        if tmp and os.path.exists(tmp): os.unlink(tmp)

def read(path):
    d=json.loads(Path(path).read_text(encoding='utf-8'))
    if not isinstance(d,dict) or d.get('schema_version')!=VERSION:
        raise ValueError('Unsupported checkpoint schema')
    expected=set(make('test','test','test').keys())
    if set(d)!=expected or d.get('resume_requires_reverification') is not True:
        raise ValueError('Invalid checkpoint shape or unsafe resume flag')
    if not isinstance(d['task_revision'],int) or d['task_revision']<1:
        raise ValueError('Invalid revision')
    if not isinstance(d['step'],int) or d['step']<0:
        raise ValueError('Invalid step')
    if not isinstance(d['action_may_have_occurred'],bool):
        raise ValueError('Invalid action flag')
    for field in expected-{'schema_version','task_revision','step',
                           'action_may_have_occurred','resume_requires_reverification'}:
        safe_text(d[field])
    if not re.fullmatch(r'[A-Za-z0-9._-]{1,100}',d['task_id']) or d['state'] not in STATES:
        raise ValueError('Invalid task id/state')
    return d

def main():
    p=argparse.ArgumentParser(description=__doc__)
    sub=p.add_subparsers(dest='cmd',required=True)
    a=sub.add_parser('save')
    for field in ('file','task-id','goal','next-step'):
        a.add_argument('--'+field,required=True)
    a.add_argument('--state',choices=sorted(STATES),default='PAUSED')
    a.add_argument('--verified',action='append',default=[])
    a.add_argument('--constraint',action='append',default=[])
    a=sub.add_parser('show')
    a.add_argument('--file',required=True)
    args=p.parse_args()
    if args.cmd=='save':
        save(args.file,make(args.task_id,args.goal,args.next_step,args.state,args.verified,args.constraint))
    else:
        print(json.dumps(read(args.file),ensure_ascii=False,indent=2))

if __name__=='__main__':
    main()
