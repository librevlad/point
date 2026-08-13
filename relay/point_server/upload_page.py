"""Страница, на которой чужой человек кладёт файл в чужой ящик.

Её видит не владелец Point, а посторонний, открывший незнакомую ссылку: он не знает ни
продукта, ни его устройства. Поэтому здесь ровно одно действие и ни одного слова о Point.

Портал — тот же знак, что и в приложении (`core/ui/Portal.kt`): фиолетово-голубое кольцо.
Здесь он ещё и работает индикатором — кольцо заполняется по мере отправки, и объект буквально
уходит через портал. Это ответ на «виснет у клиента»: сервер принимает 4 МБ за сотые доли
секунды, но снимок по мобильной связи идёт секунды, и раньше страница всё это время молчала.

Скрипт — своё, встроенное, без единого чужого домена: человек и так открыл незнакомую ссылку.
Без скрипта страница остаётся обычной формой и работает: `no-js` возвращает родное поле файла.
"""
from __future__ import annotations

UPLOAD_HEAD = """
<style>
.portal{position:relative;width:190px;height:190px;margin:4px auto 18px;display:grid;
place-items:center;cursor:pointer;-webkit-tap-highlight-color:transparent}
.portal::before{content:'';position:absolute;inset:-16%;border-radius:50%;
background:radial-gradient(circle,rgba(123,92,255,.30),rgba(0,224,255,.13) 55%,transparent 72%)}
.ring{position:absolute;inset:0;border-radius:50%;
-webkit-mask:radial-gradient(farthest-side,transparent calc(100% - var(--w)),#000 0);
mask:radial-gradient(farthest-side,transparent calc(100% - var(--w)),#000 0)}
.r-out{--w:6px;background:conic-gradient(from 0deg,#7B5CFF,#00E0FF,#B39DFF,#7B5CFF);
opacity:.85;animation:spin 16s linear infinite}
.r-in{--w:4px;inset:16%;background:conic-gradient(from 180deg,#00E0FF,#7B5CFF,#00E0FF);
opacity:.5;animation:spin 11s linear infinite reverse}
.r-fill{--w:6px;--p:0;opacity:0;transition:opacity .2s;
background:conic-gradient(#00E0FF calc(var(--p)*1%),transparent 0)}
.sending .r-fill{opacity:1}
.sending .r-out{opacity:.25}
.core{position:relative;text-align:center;line-height:1.35;padding:0 22px}
.core b{display:block;font-size:17px;font-weight:600;color:#EAF0FF}
.core span{margin-top:4px;font-size:13px;color:#9AA3B2;max-width:132px;overflow:hidden;
display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;word-break:break-word}
.portal:focus-within .r-out{opacity:1}
.over .r-out{opacity:1;--w:8px}
@keyframes spin{to{transform:rotate(1turn)}}
@media (prefers-reduced-motion:reduce){.r-out,.r-in{animation:none}}
#file{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;
clip:rect(0 0 0 0);white-space:nowrap;border:0}
.no-js #file{position:static;width:100%;height:auto;margin:0 0 12px;clip:auto;
padding:14px;border:1px dashed #FFFFFF2E;border-radius:12px;background:#00000033;color:#A8ADB8}
.no-js .portal{display:none}
button[disabled]{opacity:.5;cursor:default}
.tabs{display:flex;gap:4px;background:#00000033;border-radius:12px;padding:4px;margin:0 0 16px}
.tab{flex:1;padding:9px 0;border:0;border-radius:9px;background:transparent;color:#9AA3B2;
font-size:14px;font-weight:500;cursor:pointer}
.tab.on{background:#1A1D25;color:#EAF0FF;font-weight:600}
/* Отправка состоялась: вкладки видно, но нажимать нечего — страница больше не принимает. */
.tab.done{opacity:.4;cursor:default}
#text{display:none;width:100%;margin:0 0 14px;padding:12px;border:1px solid #FFFFFF2E;
border-radius:12px;background:#00000033;color:#F2F3F5;font:inherit;font-size:15px;resize:vertical}
.no-js #text{display:block}
.no-js .tabs{display:none}
.said{margin:0 0 14px;font-size:14px;line-height:1.5;min-height:1.2em}
.bad{color:#FF9A9A}
#contact{display:none}
#contact input{width:100%;box-sizing:border-box;margin:0 0 8px;padding:12px 14px;
border-radius:12px;border:1px solid #ffffff14;background:#1B1E27;color:#EAF0FF;font-size:15px}
#contact input::placeholder{color:#6C7488}
#place{display:none;text-align:center;padding:10px 0 4px;color:#9AA3B2;font-size:14px}
</style>
"""


def upload_body() -> str:
    return (
        "<h1>Отправить в Point</h1>"
        "<p>Придёт человеку, который дал вам эту ссылку.</p>"
        '<div class="tabs" id="tabs">'
        '<button type="button" class="tab on" id="tab-file">Файл</button>'
        '<button type="button" class="tab" id="tab-text">Текст</button>'
        # Отдача уже понимает контакт и место — страницами с именем и картой (#737, #883).
        # Приём умел только файл и текст: контакт можно было прислать разве что файлом, а
        # место — никак (#916).
        '<button type="button" class="tab" id="tab-contact">Контакт</button>'
        '<button type="button" class="tab" id="tab-place">Место</button>'
        "</div>"
        '<form method="post" enctype="multipart/form-data" id="f">'
        '<label class="portal" id="zone" for="file">'
        '<span class="ring r-out"></span><span class="ring r-in"></span>'
        '<span class="ring r-fill" id="fill"></span>'
        '<span class="core" id="core"><b>Выберите файл</b><span id="name">или перетащите сюда</span></span>'
        "</label>"
        '<input type="file" name="file" id="file">'
        '<textarea name="text" id="text" rows="7" placeholder="Напишите или вставьте текст…"></textarea>'
        '<div id="contact">'
        '<input id="c-name" placeholder="Имя" autocomplete="name">'
        '<input id="c-phone" placeholder="Телефон" inputmode="tel" autocomplete="tel">'
        '<input id="c-mail" placeholder="Почта — если есть" inputmode="email" autocomplete="email">'
        "</div>"
        '<p id="place">Point попросит у браузера, где вы сейчас. Точку увидит только тот, '
        "кто дал вам ссылку.</p>"
        '<p class="said" id="said" role="status" aria-live="polite"></p>'
        '<button type="submit" id="go">Отправить файл</button>'
        "</form>"
        '<small id="note">Файл уходит сразу после нажатия — до 50 МБ. '
        "Пока идёт отправка, не закрывайте страницу.</small>"
        "<script>%s</script>" % UPLOAD_SCRIPT
    )


# Прогресс отправки даёт только XMLHttpRequest: fetch о ходе загрузки не рассказывает.
UPLOAD_SCRIPT = r"""
(function(){
 var f=document.getElementById('f'),i=document.getElementById('file'),
     zone=document.getElementById('zone'),name=document.getElementById('name'),
     core=document.getElementById('core'),fill=document.getElementById('fill'),
     said=document.getElementById('said'),go=document.getElementById('go'),
     note=document.getElementById('note');
 document.documentElement.className=document.documentElement.className.replace(/\bno-js\b/,'');

 function human(n){
  if(n<1024) return n+' Б';
  if(n<1048576) return (n/1024).toFixed(0)+' КБ';
  return (n/1048576).toFixed(1)+' МБ';
 }
 function say(text,bad){ said.textContent=text||''; said.className='said'+(bad?' bad':''); }
 function chosen(){
  var file=i.files&&i.files[0];
  if(!file){ name.textContent='или перетащите сюда'; return; }
  name.textContent=file.name+' · '+human(file.size);
  say('');
 }
 i.addEventListener('change',chosen);

 // Вкладки «Файл | Текст». Ссылку чаще всего дают ради куска текста — адреса, номера
 // заказа, обрывка переписки, — а форма умела только файл.
 var mode='file', text=document.getElementById('text'),
     contact=document.getElementById('contact'), place=document.getElementById('place'),
     tabs={file:document.getElementById('tab-file'),text:document.getElementById('tab-text'),
           contact:document.getElementById('tab-contact'),place:document.getElementById('tab-place')},
     verbs={file:'Отправить файл',text:'Отправить текст',
            contact:'Отправить контакт',place:'Отправить место'},
     notes={
      file:'Файл уходит сразу после нажатия — до 50 МБ. Пока идёт отправка, не закрывайте страницу.',
      text:'Текст придёт текстом, а не файлом.',
      contact:'Придёт контактом — его можно сохранить в телефон одним нажатием.',
      place:'Придёт точкой на карте.'};
 // Отправка была одна и она состоялась — страница больше ничего не принимает (#928).
 // Раньше кнопка убиралась, а вкладки продолжали работать: человек жал «Контакт», видел
 // живую форму с полями и обещанием — и не находил, чем отправить. «Готово» при этом
 // стиралось. Ссылка приёма — на одну вещь; второе присылается по новой ссылке.
 var sent=false;
 function pick(next){
  if(sent) return;
  mode=next;
  for(var k in tabs){ tabs[k].className='tab'+(k===mode?' on':''); }
  zone.style.display=mode==='file'?'':'none';
  text.style.display=mode==='text'?'block':'none';
  contact.style.display=mode==='contact'?'block':'none';
  place.style.display=mode==='place'?'block':'none';
  go.textContent=verbs[mode];
  note.textContent=notes[mode];
  say('');
 }
 for(var key in tabs){ (function(k){ tabs[k].addEventListener('click',function(){ pick(k); }); })(key); }

 ['dragenter','dragover'].forEach(function(e){
  zone.addEventListener(e,function(ev){ ev.preventDefault(); zone.classList.add('over'); });
 });
 ['dragleave','drop'].forEach(function(e){
  zone.addEventListener(e,function(ev){ ev.preventDefault(); zone.classList.remove('over'); });
 });
 zone.addEventListener('drop',function(ev){
  if(!ev.dataTransfer||!ev.dataTransfer.files.length) return;
  i.files=ev.dataTransfer.files; chosen();
 });

 function vcard(){
  var n=(document.getElementById('c-name').value||'').trim(),
      p=(document.getElementById('c-phone').value||'').trim(),
      m=(document.getElementById('c-mail').value||'').trim();
  if(!n&&!p&&!m) return null;
  var out='BEGIN:VCARD\r\nVERSION:3.0\r\n';
  if(n) out+='FN:'+n+'\r\n';
  if(p) out+='TEL;TYPE=CELL:'+p+'\r\n';
  if(m) out+='EMAIL:'+m+'\r\n';
  return {text:out+'END:VCARD\r\n', name:(n||'Контакт')+'.vcf'};
 }

 function send(data){
  var x=new XMLHttpRequest();
  x.open('POST',location.pathname);
  go.disabled=true; zone.classList.add('sending');
  core.innerHTML='<b id="pc">0%</b><span>отправляю</span>';
  var pc=document.getElementById('pc');
  say('');
  x.upload.onprogress=function(e){
   if(!e.lengthComputable) return;
   var p=Math.round(e.loaded*100/e.total);
   fill.style.setProperty('--p',p); pc.textContent=p+'%';
  };
  x.onload=function(){
   if(x.status===200){
    fill.style.setProperty('--p',100);
    core.innerHTML='<b>Готово</b><span>'+({file:'файл ушёл',text:'текст ушёл',
      contact:'контакт ушёл',place:'место ушло'}[mode])+'</span>';
    note.textContent='Можно закрывать страницу. Чтобы прислать ещё что-то, попросите новую ссылку.';
    sent=true;
    for(var k in tabs){ tabs[k].className='tab done'; }
    say(''); go.remove();
   } else {
    fail(x.status===404?'Ссылка больше не работает — попросите новую.'
        :x.status===507?'Файл слишком большой для этого ящика.'
        :'Сервер не принял отправку. Попробуйте ещё раз.');
   }
  };
  x.onerror=function(){ fail('Связь прервалась. Проверьте интернет и попробуйте ещё раз.'); };
  x.onabort=function(){ fail('Отправка прервана.'); };
  x.send(data);
 }

 f.addEventListener('submit',function(ev){
  ev.preventDefault();
  var file=i.files&&i.files[0];
  var body=(text.value||'').trim();
  if(mode==='text'&&!body){ say('Напишите текст — пока отправлять нечего',true); return; }
  if(mode==='file'&&!file){ say('Выберите файл — пока отправлять нечего',true); return; }

  // Место спрашивается у браузера здесь и сейчас: чужой человек нажал «Отправить место»,
  // и это его согласие. Без разрешения не уходит ничего (#916).
  if(mode==='place'){
   if(!navigator.geolocation){ say('Браузер не умеет определять место',true); return; }
   say('Спрашиваю, где вы…');
   navigator.geolocation.getCurrentPosition(function(pos){
    var d=new FormData();
    d.append('text',pos.coords.latitude.toFixed(6)+', '+pos.coords.longitude.toFixed(6));
    d.append('name','Место.txt');
    send(d);
   },function(){ say('Место не разрешено — точку отправить нечем',true); },
   {enableHighAccuracy:true,timeout:15000});
   return;
  }

  if(mode==='contact'){
   var card=vcard();
   if(!card){ say('Заполните хотя бы имя или телефон',true); return; }
   var dc=new FormData();
   dc.append('text',card.text);
   dc.append('name',card.name);
   send(dc);
   return;
  }

  var data=new FormData();
  if(mode==='text'){ data.append('text',body); } else { data.append('file',file,file.name); }
  send(data);
 });

 function fail(text){
  zone.classList.remove('sending'); go.disabled=false;
  core.innerHTML='<b>Выберите файл</b><span id="name"></span>';
  name=document.getElementById('name'); chosen();
  say(text,true);
 }
})();
"""
