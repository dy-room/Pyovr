const fs=require('fs');
const P=new Function(fs.readFileSync('/home/claude/build/planner.js','utf8')+';return PuyoPlanner;')();
const R=new Function(fs.readFileSync('/home/claude/build/reader.js','utf8')+';return PuyoReader;')();
const T=new Function(fs.readFileSync('/home/claude/build/templates.js','utf8')+';return PUYO_TPL;')();
let seed=12345; function rnd(){seed=(seed*1103515245+12345)&0x7fffffff;return seed/0x7fffffff;}
const pick=s=>s[Math.floor(rnd()*s.length)];
const out=[];
for(let t=0;t<40;t++){
  const grid=[];
  for(let r=0;r<6;r++){const row=[];for(let c=0;c<8;c++){
    const x=rnd(); row.push(x<0.06?'H':x<0.1?'X':x<0.15?'O':x<0.2?'.':pick('RBGYP'));}grid.push(row);}
  const preview=[];for(let c=0;c<8;c++)preview.push(rnd()<0.2?'?':pick('RBGYP'));
  const weights={R:3,B:3,G:3,Y:3,P:3}; 'RBGYP'.split('').forEach(k=>{weights[k]=rnd()<0.5?3:0.4;});
  const heal=rnd()<0.5?0:5, limit=3+Math.floor(rnd()*3), preFirst=rnd()<0.5;
  const chance=[];for(let q=0;q<3;q++){if(rnd()<0.5)chance.push([Math.floor(rnd()*6),Math.floor(rnd()*8)]);}
  const prismW=weights.R*2; const traceW=rnd()<0.5?0:1, dd=rnd()<0.5;
  const job=P.buildJob({mode:'normal',grid,preview,weights,heal,chanceW:30,prismW,chance,limit,preFirst,traceW,dedupe:dd});
  const s=P.createSearcher(job);const lists=[];for(let r=0;r<48;r++)lists.push(s.searchRoot(r).top);
  const top=P.mergeTop(lists,3,dd);
  out.push(['C',grid.map(r=>r.join('')).join(''),preview.join(''),'RBGYP'.split('').map(k=>weights[k]).join(','),heal,prismW,30,limit,preFirst?1:0,chance.map(x=>x.join(',')).join(';')||'-',traceW,dd?1:0].join(' '));
  top.forEach(g=>out.push(['T',g.score.toPrecision(17),g.pre,g.post,g.cells.join(','),g.path.join(',')].join(' ')));
  out.push('E');
}
fs.writeFileSync('planner_vectors.txt',out.join('\n')+'\n');
const rv=[];
const tpl=R.loadTemplates(T.digits);
for(const f of ['real','real2','real3','real4']){
  const j={w:1080,h:2340};
  const buf=fs.readFileSync('/home/claude/test/'+f+'.rgba');
  const w=j.w||j.width,h=j.h||j.height;
  const img={data:new Uint8ClampedArray(buf.buffer,buf.byteOffset,buf.length),width:w,height:h};
  const b=R.readBoard(img,{});const ch=R.detectChancePuyos(img,b.grid,{});const n=R.readRemaining(img,tpl);
  rv.push(['R','/home/claude/test/'+f+'.rgba',w,h,b.grid.map(r=>r.join('')).join(''),b.preview.join(''),ch.map(x=>x.join(',')).join(';')||'-',n.n].join(' '));
}
fs.writeFileSync('reader_vectors.txt',rv.join('\n')+'\n');
console.log(rv.join('\n'));
