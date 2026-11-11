import fs from 'node:fs';import path from 'node:path';import {pathToFileURL,fileURLToPath} from 'node:url';
const UA='AsanNetPriceBot/1.0 (+https://t.me/ASANNETT)';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const FA='۰۱۲۳۴۵۶۷۸۹',AR='٠١٢٣٤٥٦٧٨٩';
const norm=s=>String(s).replace(/[۰-۹]/g,d=>FA.indexOf(d)).replace(/[٠-٩]/g,d=>AR.indexOf(d)).replace(/[٬،,]/g,'').replace(/٫/g,'.');
const NUM='\\d{1,3}(?:\\.\\d{3})+|\\d+(?:\\.\\d+)?';
function amounts(ptxt,m){const t=norm(ptxt);let x=t.match(/(\d+(?:\.\d+)?)\s*میلیون(?:\s*و\s*(\d+(?:\.\d+)?)\s*هزار)?(?:\s*و\s*(\d+))?/);
 if(x)return[Math.round(x[1]*1e6+(x[2]||0)*1e3+(+x[3]||0))];
 x=t.match(/(\d+(?:\.\d+)?)\s*هزار\s*و\s*(\d+)/);if(x)return[Math.round(x[1]*1e3+ +x[2])];
 const hz=/هزار/.test(t),mm=hz?1000:m;
 return[...t.replace(/هزار تومان|هزار|تومان|ریال/g,' ').matchAll(new RegExp(NUM,'g'))].map(y=>{const s=y[0];if(/^\d{1,3}(\.\d{3}){2,}$/.test(s))return+s.replace(/\./g,'');const n=parseFloat(s);return(!hz&&n>=10000)?Math.round(n):Math.round(n*mm);});}
const CATS=[['پروتئین',/مرغ|گوشت|گوساله|گوسفند|ماهی|تخم|دنبه|بوقلمون|شترمرغ|تیلاپیا|سوسیس|کالباس/],['لبنیات',/شیر|ماست|پنیر|کره|خامه|دوغ|کشک/],['روغن',/روغن/],['برنج و غلات',/برنج|ماکارونی|آرد|گندم|نان|رشته/],['قند و شیرینی',/شکر|قند|عسل|نبات/],['نوشیدنی',/چای|قهوه|آب معدنی/],['حبوبات',/عدس|نخود|لوبیا|لپه|ماش|سویا/]];
const cat=n=>(CATS.find(c=>c[1].test(n))||['سایر'])[0];
const staple=n=>cat(n)!=='سایر'||/نمک|رب |سیب|پیاز|گوجه|خیار|دستمال|پودر|مایع|شامپو/.test(n);
function parseLine(line,mult){
 const raw=String(line).replace(/\s+/g,' ').trim();if(!raw)return null;
 const cells=raw.split(/\t|\|/).map(x=>x.trim()).filter(Boolean);let name,ptxt,unit='';
 if(cells.length>=2){name=cells[0];ptxt=cells[cells.length-1];if(cells.length>2)unit=cells[1];}
 else{const m=norm(raw).match(new RegExp('^(.*?)\\s*((?:'+NUM+')(?:\\s*(?:-|–|تا)\\s*(?:'+NUM+'))?)\\s*(?:هزار تومان|تومان)?$'));if(!m)return null;name=m[1].trim();ptxt=m[2];}
 if(!name||name.length>70)return null;
 const nums=amounts(ptxt,mult);
 if(!nums.length||nums.some(n=>!(n>=5000&&n<=5e7)))return null;
 const min=Math.min(...nums),max=Math.max(...nums);
 const u=unit||((norm(name).match(/\d+(?:\.\d+)?\s*(?:گرم|کیلوگرم|کیلو|لیتر)/)||[])[0])||(/شانه/.test(name)?'شانه ۳۰ عددی':'کیلوگرم');
 return{name:name.replace(/\s*[,،:]+$/,''),unit:u,min,max,price:min===max?min:null,cat:cat(name)};}
export {norm,parseLine,cat,staple};
const txt=c=>c.replace(/<[^>]+>/g,' ').replace(/&nbsp;|&zwnj;/g,' ').replace(/&amp;/g,'&').replace(/\s+/g,' ').trim();
export function j2g(jy,jm,jd){jy-=979;jm-=1;jd-=1;let jdn=365*jy+Math.floor(jy/33)*8+Math.floor(((jy%33)+3)/4);for(let i=0;i<jm;i++)jdn+=i<6?31:30;jdn+=jd;let g=jdn+79,gy=1600+400*Math.floor(g/146097);g%=146097;let leap=true;
 if(g>=36525){g--;gy+=100*Math.floor(g/36524);g%=36524;if(g>=365)g++;else leap=false;}gy+=4*Math.floor(g/1461);g%=1461;if(g>=366){leap=false;g--;gy+=Math.floor(g/365);g%=365;}
 const gm=[31,leap?29:28,31,30,31,30,31,31,30,31,30,31];let i=0;for(;g>=gm[i];i++)g-=gm[i];return new Date(Date.UTC(gy,i,g+1));}
export function pubDate(html){
 const m=html.match(/article:published_time["'][^>]*content=["']([^"']+)/i)||html.match(/content=["']([^"']+)["'][^>]*property=["']article:published_time/i)||html.match(/"datePublished"\s*:\s*"([^"]+)"/)||html.match(/<time[^>]*datetime=["']([^"']+)/i);
 if(m){const d=new Date(m[1]);if(!isNaN(d))return d.toISOString().slice(0,10);}
 const j=norm(txt(html)).match(/\b(14[01]\d)\/(\d\d?)\/(\d\d?)\b/);if(j){const d=j2g(+j[1],+j[2],+j[3]);if(!isNaN(d))return d.toISOString().slice(0,10);}
 return null;}
export function rowsOf(html){const out=[];
 for(const tb of html.match(/<table[\s\S]*?<\/table>/gi)||[]){
  const cells=(tb.match(/<tr[\s\S]*?<\/tr>/gi)||[]).map(tr=>(tr.match(/<t[dh][\s\S]*?<\/t[dh]>/gi)||[]).map(txt));if(!cells.length)continue;
  let pi=-1,ui=-1,st=0;const h=cells[0];
  if(h.some(c=>/قیمت/.test(c))&&!h.some(c=>/\d/.test(c))){pi=h.findIndex(c=>/قیمت/.test(c));ui=h.findIndex((c,i)=>i!==pi&&/وزن|بسته|واحد|تعداد/.test(c));st=1;}
  for(const r of cells.slice(st)){if(r.length<2)continue;const p=pi>=0&&r[pi]?r[pi]:r[r.length-1],u=ui>=0?(r[ui]||''):(r.length>2?r[1]:'');out.push([r[0],u,p].filter((x,i)=>i!==1||x).join(' | '));}}
 return out;}
const rb={};
async function robotsAllows(u){try{const o=new URL(u);if(!rb[o.origin]){const r=await fetch(o.origin+'/robots.txt',{headers:{'User-Agent':UA},signal:AbortSignal.timeout(15000)});rb[o.origin]=r.ok?await r.text():'';}
 let ap=false,dis=[],al=[];for(const l of rb[o.origin].split('\n')){const m=l.split('#')[0].match(/^\s*([\w-]+)\s*:\s*(.*)$/);if(!m)continue;const k=m[1].toLowerCase(),v=m[2].trim();
  if(k==='user-agent')ap=v==='*'||UA.toLowerCase().includes(v.toLowerCase());else if(ap&&k==='disallow'&&v)dis.push(v);else if(ap&&k==='allow'&&v)al.push(v);}
 const p=decodeURI(o.pathname)+o.search,lg=a=>a.filter(x=>p.startsWith(x)).sort((a,b)=>b.length-a.length)[0];const d=lg(dis),a=lg(al);return !d||(a&&a.length>=d.length);}catch{return true}}
const get=async u=>{const r=await fetch(u,{headers:{'User-Agent':UA,'Accept-Language':'fa'},signal:AbortSignal.timeout(25000)});if(!r.ok)throw new Error('HTTP '+r.status);return r.text();};

// ───────── نسخه‌ی ربات آسان نت: منابع اصلی + منابع جایگزین + داده‌ی آماده (اگه همه‌ی منابع خطا بدن) ─────────
export async function main(){
 const dir=path.dirname(fileURLToPath(import.meta.url));
 const cfg=JSON.parse(fs.readFileSync(path.join(dir,'sources.json'),'utf8'));
 const outFile=process.env.NABZE_OUT||path.join(dir,'data','scraped.json');
 const pause=process.env.NABZE_FAST?0:3000;
 const now=Date.now(),maxAge=cfg.maxAgeDays||14,log=[];
 const L=(id,name,st,msg)=>{log.push({id,name,status:st,msg,at:new Date().toISOString()});console.log(id,st,msg);};
 const pat=new RegExp(cfg.linkPattern||'قیمت.*(کالاهای اساسی|مرغ|تخم|برنج|روغن|گوشت|لبنیات)');
 const minItems=cfg.minItems||150,items=[],done=new Set();

 async function collect(listings,articles,maxArticles){
  const targets=new Map();
  for(const s of listings){
   try{if(!(await robotsAllows(s.url))){L(s.id,s.name,'skipped','robots.txt اجازه نمی‌دهد');continue;}
    const html=await get(s.url);let n=0;
    for(const m of html.matchAll(/<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi)){
     if(n>=(cfg.maxPerListing||3))break;const t=txt(m[2]);if(!pat.test(t))continue;
     try{const u=new URL(m[1],s.url).href;if(!targets.has(u)&&!done.has(u)){targets.set(u,s);n++;}}catch{}}
    L(s.id,s.name,n?'ok':'error',n?n+' گزارش پیدا شد':'لینکی با عنوان قیمت کالا در صفحه فهرست نبود');
   }catch(e){L(s.id,s.name,'error',String(e.message||e));}
   await sleep(pause);}
  for(const a of articles||[])if(a.enabled!==false&&!done.has(a.url))targets.set(a.url,{id:a.id||'article',name:a.name||new URL(a.url).hostname});
  let c=0;
  for(const [u,s] of targets){
   if(c++>=maxArticles)break;const id=s.id+':'+u.slice(-30);
   try{if(!(await robotsAllows(u))){L(id,s.name,'skipped','robots.txt اجازه نمی‌دهد');continue;}
    const html=await get(u),d=pubDate(html);
    if(!d){L(id,s.name,'skipped','تاریخ انتشار پیدا نشد؛ برای جلوگیری از قیمت قدیمی رد شد');continue;}
    if((now-new Date(d))/864e5>maxAge){L(id,s.name,'skipped','گزارش قدیمی است ('+d+')');continue;}
    const got=rowsOf(html).map(r=>parseLine(r,1000)).filter(x=>x&&staple(x.name));
    if(!got.length){L(id,s.name,'error','جدول قیمت قابل تشخیص نبود');continue;}
    got.forEach(x=>items.push({...x,source:s.name,url:u,date:new Date().toISOString().slice(0,10),reportDate:d,kind:'scraped'}));
    done.add(u);L(id,s.name,'ok',got.length+' قلم · '+d);
   }catch(e){L(id,s.name,'error',String(e.message||e));}
   await sleep(pause);}
 }

 // ۱) منابع اصلی
 await collect((cfg.listings||[]).filter(s=>s.enabled),cfg.articles,cfg.maxArticles||30);
 // ۲) اگه قلم‌های کافی نبود (منبعی خطا داده)، منابع جایگزین
 if(items.length<minItems){
  L('backup','منابع جایگزین','info','فقط '+items.length+' قلم از منابع اصلی گرفته شد؛ منابع جایگزین بررسی می‌شن');
  await collect((cfg.backupListings||[]).filter(s=>s.enabled!==false),cfg.backupArticles,cfg.maxBackupArticles||20);
 }
 // ۳) آخرین راه: داده‌ی آماده‌ی ابزار شخصی (که اسکرپر خودش اون رو به‌روز می‌کنه)
 if(items.length<minItems){
  for(const f of cfg.fallbackData||[]){
   try{const r=JSON.parse(await get(f.url));
    const extra=(r.items||[]).filter(i=>i&&i.reportDate&&(now-new Date(i.reportDate))/864e5<=maxAge*2);
    extra.forEach(i=>items.push({...i,source:i.source||f.name,kind:'scraped'}));
    L(f.id,f.name,extra.length?'ok':'error',extra.length+' قلم از داده‌ی آماده');
    if(items.length>=minItems)break;
   }catch(e){L(f.id,f.name,'error',String(e.message||e));}}
 }
 fs.mkdirSync(path.dirname(outFile),{recursive:true});
 fs.writeFileSync(outFile,JSON.stringify({updated:new Date().toISOString(),items,log},null,1));
 console.log('جمع کل:',items.length,'قلم');
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href)main();
