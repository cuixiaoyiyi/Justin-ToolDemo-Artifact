#!/usr/bin/env python3
"""Align HITS's top-level test type with its filename; preserve comments and all literals."""
import pathlib, re, shutil, sys

TEXTBLOCK = r'"""(?:\\[\s\S]|(?!""")[^\\])*"""'
LEX = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|'+TEXTBLOCK+r'|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27|[A-Za-z_$][\w$]*|\s+|[^\s]')
IDENT = re.compile(r'[A-Za-z_$][\w$]*\Z')

def code_tokens(text):
    return [m for m in LEX.finditer(text) if not m.group().isspace() and not m.group().startswith(('//','/*','"',"'"))]

def top_level_types(tokens):
    depth=0;previous='';prefix=[];found=[]
    for i,t in enumerate(tokens):
        value=t.group()
        if depth==0 and value in ['class','record','enum','interface'] and previous!='.' and i+1<len(tokens):
            name=tokens[i+1].group()
            if IDENT.fullmatch(name):found.append((name,'public' in prefix))
        if value=='{':depth+=1
        elif value=='}':depth-=1
        if depth==0 and value in [';', '}']:prefix=[]
        elif depth==0:prefix.append(value)
        previous=value
    return found

def transform(text,want):
    lex=list(LEX.finditer(text))
    if any(m.group() in ['"',"'"] or (m.group()=='/' and text[m.start():].startswith('/*')) for m in lex):
        return text,None  # Truncated model output is left to compiler diagnostics.
    tokens=code_tokens(text);types=top_level_types(tokens)
    if any(name==want for name,_ in types):return text,None
    base=re.sub(r'_slice\d+$','',want)
    candidates=[name for name,_ in types if name==base]
    if not candidates:candidates=[name for name,public in types if public]
    if not candidates and len(types)==1:candidates=[types[0][0]]
    if len(candidates)!=1:return text,None
    have=candidates[0];pieces=[];last=0
    for token in tokens:
        if token.group()!=have:continue
        pieces.extend([text[last:token.start()],want]);last=token.end()
    pieces.append(text[last:]);return ''.join(pieces),have

def main(src,dst):
    src=pathlib.Path(src).resolve();dst=pathlib.Path(dst).resolve()
    if src==dst or dst in src.parents or src in dst.parents:raise ValueError('source and destination must be separate trees')
    if dst.exists():shutil.rmtree(dst)
    shutil.copytree(src,dst);renamed=untouched=0
    for p in dst.rglob('*.java'):
        text=p.read_text(encoding='utf-8',errors='replace');fixed,have=transform(text,p.stem)
        if have is None:untouched+=1;continue
        p.write_text(fixed,encoding='utf-8');renamed+=1
    print('%d files renamed by code identifiers; %d unchanged; literals and comments preserved'%(renamed,untouched))

if __name__=='__main__':main(*sys.argv[1:])
