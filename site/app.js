// Демо «путешествие по объекту» на лендинге Point.
//
// Данные (иконки, объекты, действия, переходы) перенесены дословно из макета Claude Design
// (`Point Site.dc.html`, issue #282) — менять их здесь без макета не нужно. Логика написана
// заново на ванильном JS: макет исполнялся рантаймом дизайн-инструмента, которому нужен React,
// а лендингу лишняя зависимость ни к чему.
//
// Это витрина, а не приложение: те же данные в настоящем Point выводятся из Capability.accepts().

(function () {
'use strict';


const I = {
  text: 'M4 6h16M4 12h12M4 18h8',
  table: 'M3 4h18v16H3zM3 10h18M9 4v16',
  ai: 'M12 3l1.7 4.6L18 9.5l-4.3 1.9L12 16l-1.7-4.6L6 9.5l4.3-1.9zM18 16l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z',
  doc: 'M6 3h8l4 4v14H6zM14 3v4h4',
  image: 'M3 5h18v14H3zM3 15l5-5 4 4 3-3 6 6M8 9h.01',
  scan: 'M4 8V4h4M20 8V4h-4M4 16v4h4M20 16v4h-4M7 12h10',
  cut: 'M6 5a2.5 2.5 0 1 1-3 3.9M7 7l11 10M17 7L7 17M6 19a2.5 2.5 0 1 0-3-3.9',
  share: 'M4 12v7h16v-7M12 3v12M8 7l4-4 4 4',
  save: 'M12 3v12M8 11l4 4 4-4M4 19h16',
  copy: 'M9 9h11v11H9zM4 15V4h11',
  translate: 'M4 6h9M8 6c0 5-1.8 8-5 10M6 10c1 3 3 5 6.5 5.8M13 20l4-9 4 9M14.6 17h4.8',
  phone: 'M5 4h4l2 5-2 2a12 12 0 0 0 4 4l2-2 5 2v4a15 15 0 0 1-15-15z',
  list: 'M9 6h11M9 12h11M9 18h11M4 5.5l1.4 1.4L4.2 8.3M4 11.5l1.4 1.4L4.2 14.3M4 17.5l1.4 1.4L4.2 20.3',
  pc: 'M3 5h18v11H3zM8 20h8M12 16v4',
  qr: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 18h2v2h-2z',
  zip: 'M4 7h16v13H4zM2 4h20v3H2zM10 11h4v3h-4z',
  folder: 'M3 6h6l2 2h10v11H3z',
  person: 'M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21c0-4 3.6-6 8-6s8 2 8 6',
  link: 'M9.5 14.5l5-5M7 12l-2 2a3.5 3.5 0 0 0 5 5l2-2M17 12l2-2a3.5 3.5 0 0 0-5-5l-2 2',
  open: 'M14 4h6v6M20 4l-8 8M18 13v7H4V6h7',
  merge: 'M6 4v6a4 4 0 0 0 4 4h8M18 10l3 4-3 4',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  brain: 'M9 4a3 3 0 0 0-3 3 3 3 0 0 0-1 5.8V16a3 3 0 0 0 4 2.8M15 4a3 3 0 0 1 3 3 3 3 0 0 1 1 5.8V16a3 3 0 0 1-4 2.8M12 4v15',
};

const P = { v: ['rgba(123,92,255,.16)', '#C7B3FF'], c: ['rgba(0,224,255,.13)', '#8FF0FF'], n: ['#1B1E27', '#A1A6B3'] };

function a(id, label, sub, icon, group, ms, to, tint, done) {
  return { id, label, sub, icon: I[icon], group, ms, to, tint: tint || 'n', done };
}

const OBJ = {
  receipt: {
    title: 'Store receipt', sub: 'JPEG · 2.1 MB · 3024×4032', kind: 'IMAGE', icon: I.image,
    facts: ['A photo of a printed receipt', 'Text detected — 34 lines', 'Looks tabular: 12 items and a total'],
    actions: [
      a('ocr', 'Recognise text', 'On device first, cloud as fallback', 'text', 'Extract', 1300, 'text', 'c'),
      a('table', 'Extract table', 'Rows and totals → spreadsheet', 'table', 'Extract', 1700, 'table', 'c'),
      a('basket', 'Shopping list', '12 items become a checklist', 'list', 'Extract', 900, 'list'),
      a('ai', 'Ask AI', 'Sends the object, not a chat', 'ai', 'Extract', 1600, 'answer', 'c'),
      a('scan', 'Scan+', 'Dewarp, contrast, white finisher', 'scan', 'Transform', 1200, 'scan', 'v'),
      a('pdf', 'Make PDF', 'The photo folds into a page', 'doc', 'Transform', 900, 'pdf', 'v'),
      a('cut', 'Cut out subject', 'Background removed, alpha kept', 'cut', 'Transform', 1100, 'cutout'),
      a('share', 'Share', 'Straight to the system sheet', 'share', 'Send', 700, null, null, 'Shared'),
      a('save', 'Save', 'Into your gallery', 'save', 'Send', 600, null, null, 'Saved to Photos'),
      a('pc', 'Continue on PC', 'Paired desktop over your LAN', 'pc', 'Send', 800, null, 'v', 'Handed to your PC'),
    ],
  },
  text: {
    title: 'Recognised text', sub: 'TXT · 1.2 KB · 34 lines', kind: 'TEXT', icon: I.text,
    facts: ['34 lines of text', 'An IBAN was found: DE89 3704 …', 'A phone number was found: +49 30 …'],
    actions: [
      a('translate', 'Translate', 'Numbers and IBANs stay intact', 'translate', 'Extract', 1200, 'translation', 'c'),
      a('excel', 'To spreadsheet', 'Two passes, then consensus', 'table', 'Extract', 1500, 'table', 'c'),
      a('deep', 'Understand deeply', 'Who is who in this document', 'brain', 'Extract', 1600, 'answer', 'c'),
      a('vcard', 'Save as contact', 'Name, phone and email detected', 'person', 'Extract', 800, 'contact'),
      a('pdf', 'Make PDF', 'Typeset as a document', 'doc', 'Transform', 900, 'pdf', 'v'),
      a('word', 'To Word', 'Headings and styles preserved', 'doc', 'Transform', 1000, 'doc', 'v'),
      a('call', 'Call +49 30 …', 'The number found in the text', 'phone', 'Send', 600, null, 'v', 'Calling +49 30 …'),
      a('copy', 'Copy', 'To the clipboard', 'copy', 'Send', 400, null, null, 'Copied'),
      a('share', 'Share', 'Straight to the system sheet', 'share', 'Send', 700, null, null, 'Shared'),
    ],
  },
  table: {
    title: 'Spreadsheet', sub: 'XLSX · 18 KB · 12×4', kind: 'TABLE', icon: I.table,
    facts: ['12 rows × 4 columns', 'Totals column validated', 'Two recognitions agreed on the result'],
    actions: [
      a('pdf', 'Make PDF', 'Print-ready layout', 'doc', 'Transform', 900, 'pdf', 'v'),
      a('save', 'Save', 'Into Documents', 'save', 'Send', 600, null, 'v', 'Saved to Documents'),
      a('share', 'Share', 'Straight to the system sheet', 'share', 'Send', 700, null, null, 'Shared'),
      a('pc', 'Open on PC', 'Opens in your desktop editor', 'pc', 'Send', 900, null, null, 'Opened on your PC'),
    ],
  },
  pdf: {
    title: 'PDF document', sub: 'PDF · 3 pages · 1.8 MB', kind: 'PDF', icon: I.doc,
    facts: ['3 pages', 'Scanned images — no text layer', 'Page 1 reads like an invoice'],
    actions: [
      a('ocr', 'Recognise text', 'Because there is no text layer', 'text', 'Extract', 1500, 'text', 'c'),
      a('pages', 'Split into pages', 'Each page becomes an object', 'folder', 'Extract', 1000, 'collection'),
      a('translate', 'Translate', 'Keeps the layout', 'translate', 'Extract', 1600, 'translation', 'c'),
      a('save', 'Save', 'Into Documents', 'save', 'Send', 600, null, null, 'Saved to Documents'),
      a('share', 'Share', 'Straight to the system sheet', 'share', 'Send', 700, null, null, 'Shared'),
      a('pc', 'Continue on PC', 'Paired desktop over your LAN', 'pc', 'Send', 800, null, 'v', 'Handed to your PC'),
    ],
  },
  link: {
    title: 'Link', sub: 'text/uri-list · github.com', kind: 'URL', icon: I.link,
    facts: ['github.com/librevlad/point', 'Reachable — an HTML page', 'Title: Point'],
    actions: [
      a('read', 'Read as text', 'The page without the chrome', 'eye', 'Extract', 1300, 'text', 'c'),
      a('qr', 'Make QR', 'For the phone next to you', 'qr', 'Transform', 700, 'qr', 'v'),
      a('open', 'Open', 'In your browser', 'open', 'Send', 500, null, null, 'Opened in your browser'),
      a('pc', 'Open on PC', 'Lands in the desktop browser', 'pc', 'Send', 800, null, 'v', 'Opened on your PC'),
      a('copy', 'Copy', 'To the clipboard', 'copy', 'Send', 400, null, null, 'Copied'),
    ],
  },
  zip: {
    title: 'Archive', sub: 'ZIP · 24 MB · 24 entries', kind: 'ARCHIVE', icon: I.zip,
    facts: ['24 entries', 'All JPEG — an archive of photos', 'No password'],
    actions: [
      a('unpack', 'Unpack', '24 objects at once', 'folder', 'Extract', 1400, 'collection', 'c'),
      a('merge', 'Merge into PDF', 'One document, 24 pages', 'merge', 'Transform', 1800, 'pdf', 'v'),
      a('save', 'Save', 'Into Downloads', 'save', 'Send', 600, null, null, 'Saved to Downloads'),
      a('share', 'Share', 'Straight to the system sheet', 'share', 'Send', 700, null, null, 'Shared'),
    ],
  },
  collection: {
    title: 'Collection · 24 images', sub: 'from the archive', kind: 'COLLECTION', icon: I.folder,
    facts: ['24 objects inside', 'All of the same kind: image/jpeg', 'A collection is an object too'],
    actions: [
      a('enter', 'Enter the first item', 'Work on one photo', 'image', 'Extract', 600, 'receipt', 'c'),
      a('merge', 'Merge into one PDF', '24 pages in order', 'merge', 'Transform', 1700, 'pdf', 'v'),
      a('saveAll', 'Save all', '24 files into the gallery', 'save', 'Send', 1200, null, null, 'All 24 saved'),
      a('shareAll', 'Share all', 'As a multi-file share', 'share', 'Send', 900, null, null, 'All 24 shared'),
    ],
  },
  scan: {
    title: 'Cleaned scan', sub: 'PNG · 1.4 MB', kind: 'IMAGE', icon: I.scan,
    facts: ['Perspective corrected', 'Contrast normalised (CLAHE)', 'White background finisher applied'],
    actions: [
      a('ocr', 'Recognise text', 'Much easier on a clean scan', 'text', 'Extract', 1100, 'text', 'c'),
      a('pdf', 'Make PDF', 'One crisp page', 'doc', 'Transform', 800, 'pdf', 'v'),
      a('save', 'Save', 'Into your gallery', 'save', 'Send', 600, null, null, 'Saved to Photos'),
      a('share', 'Share', 'Straight to the system sheet', 'share', 'Send', 700, null, null, 'Shared'),
    ],
  },
  cutout: {
    title: 'Cut-out', sub: 'PNG · transparent', kind: 'IMAGE', icon: I.cut,
    facts: ['Subject isolated', 'Alpha edges refined', 'Ready for a new background'],
    actions: [
      a('bg', 'Replace background', 'Subject untouched', 'image', 'Transform', 1200, 'photoNew', 'v'),
      a('save', 'Save', 'Into your gallery', 'save', 'Send', 600, null, null, 'Saved to Photos'),
      a('share', 'Share', 'Straight to the system sheet', 'share', 'Send', 700, null, null, 'Shared'),
    ],
  },
  photoNew: {
    title: 'Photo · new background', sub: 'PNG · 2.6 MB', kind: 'IMAGE', icon: I.image,
    facts: ['Background replaced', 'Subject pixels untouched', 'Ready to publish'],
    actions: [
      a('pdf', 'Make PDF', 'One page', 'doc', 'Transform', 800, 'pdf', 'v'),
      a('save', 'Save', 'Into your gallery', 'save', 'Send', 600, null, null, 'Saved to Photos'),
      a('share', 'Share', 'Straight to the system sheet', 'share', 'Send', 700, null, null, 'Shared'),
    ],
  },
  list: {
    title: 'Shopping list', sub: 'MD · 12 items', kind: 'TEXT', icon: I.list,
    facts: ['12 items with prices', 'Grouped the way the shop is', 'Checkable as you walk'],
    actions: [
      a('pdf', 'Make PDF', 'For printing on the fridge', 'doc', 'Transform', 800, 'pdf', 'v'),
      a('copy', 'Copy', 'To the clipboard', 'copy', 'Send', 400, null, null, 'Copied'),
      a('share', 'Share', 'Send it to whoever shops', 'share', 'Send', 700, null, null, 'Shared'),
      a('save', 'Save', 'Into Documents', 'save', 'Send', 600, null, null, 'Saved to Documents'),
    ],
  },
  translation: {
    title: 'Translation', sub: 'TXT · English · 1.1 KB', kind: 'TEXT', icon: I.translate,
    facts: ['Russian → English', 'Numbers and the IBAN preserved', 'Line structure kept'],
    actions: [
      a('deep', 'Understand deeply', 'Summary and entities', 'brain', 'Extract', 1500, 'answer', 'c'),
      a('pdf', 'Make PDF', 'Typeset as a document', 'doc', 'Transform', 900, 'pdf', 'v'),
      a('copy', 'Copy', 'To the clipboard', 'copy', 'Send', 400, null, null, 'Copied'),
      a('share', 'Share', 'Straight to the system sheet', 'share', 'Send', 700, null, null, 'Shared'),
    ],
  },
  answer: {
    title: 'AI answer', sub: 'MD · 1 page', kind: 'TEXT', icon: I.ai,
    facts: ['Materialised as a file, never a chat', 'Eight providers behind one fallback chain', 'Your own key, your own quota'],
    actions: [
      a('translate', 'Translate', 'Into any language', 'translate', 'Extract', 1200, 'translation', 'c'),
      a('pdf', 'Make PDF', 'Typeset as a document', 'doc', 'Transform', 900, 'pdf', 'v'),
      a('copy', 'Copy', 'To the clipboard', 'copy', 'Send', 400, null, null, 'Copied'),
      a('save', 'Save', 'As a .md file', 'save', 'Send', 600, null, null, 'Saved to Documents'),
    ],
  },
  contact: {
    title: 'Contact card', sub: 'VCF · 1 contact', kind: 'CONTACT', icon: I.person,
    facts: ['Name, phone and email', 'Ready for the address book', 'Built from plain text'],
    actions: [
      a('add', 'Add to contacts', 'Straight into your address book', 'person', 'Send', 700, null, 'v', 'Added to your contacts'),
      a('call', 'Call', 'The number on the card', 'phone', 'Send', 600, null, null, 'Calling …'),
      a('share', 'Share', 'Straight to the system sheet', 'share', 'Send', 700, null, null, 'Shared'),
    ],
  },
  qr: {
    title: 'QR code', sub: 'PNG · 512×512', kind: 'IMAGE', icon: I.qr,
    facts: ['Encodes the link', 'Readable by any camera', 'High error correction'],
    actions: [
      a('save', 'Save', 'Into your gallery', 'save', 'Send', 600, null, null, 'Saved to Photos'),
      a('share', 'Share', 'Straight to the system sheet', 'share', 'Send', 700, null, null, 'Shared'),
    ],
  },
  doc: {
    title: 'Word document', sub: 'DOCX · 2 pages', kind: 'DOCUMENT', icon: I.doc,
    facts: ['Headings preserved', 'Styles taken from the source', 'Editable anywhere'],
    actions: [
      a('pdf', 'Make PDF', 'Frozen for sending', 'doc', 'Transform', 900, 'pdf', 'v'),
      a('save', 'Save', 'Into Documents', 'save', 'Send', 600, null, null, 'Saved to Documents'),
      a('share', 'Share', 'Straight to the system sheet', 'share', 'Send', 700, null, null, 'Shared'),
      a('pc', 'Open on PC', 'In your desktop editor', 'pc', 'Send', 900, null, null, 'Opened on your PC'),
    ],
  },
};

const PROGRESS = {
  ocr: 'Recognising text…', table: 'Reading the table…', basket: 'Collecting items…', ai: 'Asking the model…',
  scan: 'Straightening the scan…', pdf: 'Folding into a page…', cut: 'Finding the subject…', share: 'Handing over…',
  save: 'Saving…', pc: 'Reaching your PC…', translate: 'Translating…', excel: 'Building the spreadsheet…',
  deep: 'Reading between the lines…', vcard: 'Building the card…', word: 'Laying out the document…',
  call: 'Dialling…', copy: 'Copying…', pages: 'Splitting the pages…', read: 'Fetching the page…',
  qr: 'Drawing the code…', open: 'Opening…', unpack: 'Unpacking 24 entries…', merge: 'Merging 24 pages…',
  saveAll: 'Saving all 24…', shareAll: 'Preparing 24 files…', enter: 'Entering the item…', bg: 'Replacing the background…',
  add: 'Adding the contact…',
};

const STARTERS = [
  { id: 'receipt', label: 'Photo of a receipt', icon: I.image, tint: '#C7B3FF' },
  { id: 'pdf', label: 'Scanned PDF', icon: I.doc, tint: '#C7B3FF' },
  { id: 'link', label: 'A link', icon: I.link, tint: '#8FF0FF' },
  { id: 'zip', label: 'ZIP of photos', icon: I.zip, tint: '#8FF0FF' },
];

// ——— состояние ———————————————————————————————————————————————————————————————

var state = { stack: ['receipt'], vias: [''], phase: 'idle', act: null, steps: 0 };
var timer = null;
var root = document.getElementById('demo');
if (!root) return;

function q(sel) { return root.querySelector(sel); }
function qa(sel) { return Array.prototype.slice.call(root.querySelectorAll(sel)); }
function current() { return OBJ[state.stack[state.stack.length - 1]]; }

function svg(path, stroke, size) {
  return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 24 24" fill="none" stroke="' +
    stroke + '" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="' + path + '"></path></svg>';
}

function esc(s) {
  return String(s).replace(/[&<>"]/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
  });
}

// Показать/спрятать блок макета. Инлайновый `display` элемента запоминается один раз, чтобы
// вернуть ровно его, а не догадку: у экранов это `flex`, у карточки нового объекта — `block`.
function show(el, on) {
  if (!el) return;
  if (el.getAttribute('data-display') === null) {
    el.setAttribute('data-display', el.style.display || 'block');
  }
  el.hidden = !on;
  el.style.display = on ? el.getAttribute('data-display') : 'none';
}

// ——— переходы ————————————————————————————————————————————————————————————————

function run(act) {
  clearTimeout(timer);
  state.phase = 'running';
  state.act = act;
  render();
  // Скорость макета: 1.4x от заявленной длительности действия, но не быстрее 450 мс —
  // иначе работа не читается как работа.
  timer = setTimeout(function () {
    state.phase = 'result';
    state.steps += 1;
    render();
  }, Math.max(450, act.ms / 1.4));
}

function openMade() {
  var act = state.act;
  if (!act || !act.to) return;
  state.stack = state.stack.concat([act.to]);
  state.vias = state.vias.concat([act.label]);
  state.phase = 'idle';
  state.act = null;
  render();
}

function back() {
  clearTimeout(timer);
  if (state.phase !== 'idle') {
    state.phase = 'idle';
    state.act = null;
    render();
    return;
  }
  if (state.stack.length > 1) {
    state.stack = state.stack.slice(0, -1);
    state.vias = state.vias.slice(0, -1);
  }
  render();
}

function pick(id) {
  clearTimeout(timer);
  state = { stack: [id], vias: [''], phase: 'idle', act: null, steps: 0 };
  render();
}

// ——— отрисовка ———————————————————————————————————————————————————————————————

var HINTS = [
  'Try: receipt to Recognise text to Translate to Make PDF. Four taps, no app switching.',
  'Every screen you see here is generated from the object in front of you — nothing is scripted.',
  'Notice how the offered actions change with the object. That is the flow graph doing its job.',
];

function renderFacts(obj) {
  q('[data-facts]').innerHTML = obj.facts.map(function (text, i) {
    return '<div style="display:flex;gap:9px;align-items:flex-start;margin-top:7px;animation:factIn .5s both;animation-delay:' +
      (0.1 + i * 0.12).toFixed(2) + 's">' +
      '<span style="width:5px;height:5px;border-radius:50%;background:#00E0FF;box-shadow:0 0 8px #00E0FF;margin-top:6px;flex:none"></span>' +
      '<span style="font-size:12.5px;line-height:1.45;color:#E6E9F0">' + esc(text) + '</span></div>';
  }).join('');
}

function renderSections(obj) {
  var order = ['Extract', 'Transform', 'Send'];
  var n = 0;
  q('[data-sections]').innerHTML = order.map(function (title) {
    var items = obj.actions.filter(function (x) { return x.group === title; });
    if (!items.length) return '';
    var buttons = items.map(function (x) {
      var pal = P[x.tint] || P.n;
      var plate = x.tint === 'v' ? 'rgba(123,92,255,.16)' : pal[0];
      var delay = (0.05 + n++ * 0.035).toFixed(3) + 's';
      return '<button type="button" data-act="' + esc(x.id) + '" style="display:flex;align-items:center;gap:12px;width:100%;text-align:left;padding:11px 13px;border-radius:16px;background:#14161C;border:1px solid #242833;color:#FFFFFF;cursor:pointer;animation:bornIn .45s both;animation-delay:' + delay + '" style-hover="border-color:#7B5CFF;background:#1B1E27;box-shadow:0 0 0 3px rgba(123,92,255,.12)">' +
        '<span style="width:34px;height:34px;flex:none;border-radius:11px;background:' + plate + ';display:flex;align-items:center;justify-content:center">' + svg(x.icon, pal[1], 18) + '</span>' +
        '<span style="flex:1;min-width:0">' +
        '<span style="display:block;font-size:14px;font-weight:600;letter-spacing:-.1px">' + esc(x.label) + '</span>' +
        '<span style="display:block;font-size:11.5px;color:#A1A6B3;margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + esc(x.sub) + '</span>' +
        '</span>' +
        '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#575E70" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 6l6 6-6 6"></path></svg>' +
        '</button>';
    }).join('');
    return '<div style="margin-bottom:16px">' +
      '<div style="font-size:10px;font-weight:700;letter-spacing:1.4px;color:#A1A6B3;text-transform:uppercase;margin-bottom:9px">' +
      esc(title === 'Send' ? 'Send & keep' : title) + '</div>' +
      '<div style="display:flex;flex-direction:column;gap:7px">' + buttons + '</div></div>';
  }).join('');
}

function renderStarters() {
  q('[data-starters]').innerHTML = STARTERS.map(function (s) {
    var active = state.stack[0] === s.id;
    return '<button type="button" data-starter="' + esc(s.id) + '" style="display:flex;align-items:center;gap:10px;padding:12px 16px;border-radius:16px;background:' +
      (active ? '#1B1E27' : '#14161C') + ';border:1px solid ' + (active ? '#7B5CFF' : '#242833') +
      ';color:#FFFFFF;font-size:13.5px;font-weight:600;cursor:pointer" style-hover="border-color:#7B5CFF;background:#1B1E27">' +
      svg(s.icon, s.tint, 17) + esc(s.label) + '</button>';
  }).join('');
}

function renderTrail() {
  q('[data-trail]').innerHTML = state.stack.map(function (id, i) {
    var last = i === state.stack.length - 1;
    return '<div style="display:flex;align-items:center;gap:13px;position:relative;padding:7px 0">' +
      '<span style="width:9px;height:9px;flex:none;border-radius:50%;background:' + (last ? '#7B5CFF' : '#3A3F52') +
      ';box-shadow:' + (last ? '0 0 12px #7B5CFF' : 'none') + '"></span>' +
      '<span style="font-size:14px;font-weight:' + (last ? '700' : '500') + ';color:' + (last ? '#FFFFFF' : '#A1A6B3') + '">' +
      esc(OBJ[id].title) + '</span>' +
      '<span style="font-size:11.5px;color:#575E70">' + esc(i === 0 ? 'shared into Point' : '← ' + state.vias[i]) + '</span>' +
      '</div>';
  }).join('');
}

function render() {
  var obj = current();
  var act = state.act;
  var made = act && act.to ? OBJ[act.to] : null;

  q('[data-screen-title]').textContent =
    state.phase === 'running' ? 'Working' : state.phase === 'result' ? 'Result' : 'Object';

  qa('[data-back]').forEach(function (b) {
    b.style.opacity = (state.stack.length > 1 || state.phase !== 'idle') ? '1' : '.35';
  });

  // Не `hidden`: у экранов из макета инлайновый `display:flex`, а он сильнее правила
  // браузера `[hidden]{display:none}` — спрятанный экран остался бы на виду.
  qa('[data-screen]').forEach(function (el) {
    show(el, el.getAttribute('data-screen') === state.phase);
  });

  if (state.phase === 'idle') {
    q('[data-obj-icon]').setAttribute('d', obj.icon);
    q('[data-obj-title]').textContent = obj.title;
    q('[data-obj-sub]').textContent = obj.sub;
    q('[data-obj-kind]').textContent = obj.kind;
    renderFacts(obj);
    renderSections(obj);
  } else if (state.phase === 'running' && act) {
    q('[data-run-icon]').setAttribute('d', act.icon);
    q('[data-run-progress]').textContent = PROGRESS[act.id] || act.label + '…';
    q('[data-run-sub]').textContent = act.sub;
  } else if (state.phase === 'result') {
    q('[data-res-headline]').textContent = made ? 'Ready' : act ? (act.done || 'Done') : 'Done';
    q('[data-res-note]').textContent = made
      ? 'A new object landed in your journey.'
      : 'Nothing else to do here — the object left Point.';
    show(q('[data-res-made]'), !!made);
    show(q('[data-res-terminal]'), !made);
    if (made) {
      q('[data-made-icon]').setAttribute('d', made.icon);
      q('[data-made-title]').textContent = made.title;
      q('[data-made-sub]').textContent = made.sub;
    }
  }

  renderStarters();
  renderTrail();
  q('[data-hint]').textContent = HINTS[Math.min(state.steps, HINTS.length - 1)];
  q('[data-step-label]').textContent =
    state.steps === 0 ? 'no steps taken' : state.steps === 1 ? '1 step taken' : state.steps + ' steps taken';
  q('[data-action-count]').textContent = obj.actions.length;
  applyHover(root);
}

// ——— события —————————————————————————————————————————————————————————————————

root.addEventListener('click', function (e) {
  var el = e.target.closest('[data-act],[data-starter],[data-back],[data-cancel],[data-open-made]');
  if (!el || !root.contains(el)) return;
  if (el.hasAttribute('data-act')) {
    var id = el.getAttribute('data-act');
    var found = current().actions.filter(function (a) { return a.id === id; })[0];
    if (found) run(found);
  } else if (el.hasAttribute('data-starter')) {
    pick(el.getAttribute('data-starter'));
  } else if (el.hasAttribute('data-open-made')) {
    openMade();
  } else {
    back();
  }
});

// ——— style-hover из макета ————————————————————————————————————————————————————
//
// Макет держит наведение атрибутом `style-hover`: дизайн-инструмент пишет hover прямо рядом с
// элементом. Разметка перенесена дословно, поэтому атрибут остался, а работать его заставляет
// вот это — при наведении стили дописываются, при уходе снимаются.

function bindHover(el) {
  el.setAttribute('data-hover-bound', '');
  var extra = el.getAttribute('style-hover');
  var base = el.getAttribute('style') || '';
  var on = function () { el.setAttribute('style', base + ';' + extra); };
  var off = function () { el.setAttribute('style', base); };
  el.addEventListener('mouseenter', on);
  el.addEventListener('mouseleave', off);
  el.addEventListener('focus', on);
  el.addEventListener('blur', off);
}

function applyHover(scope) {
  Array.prototype.forEach.call(
    scope.querySelectorAll('[style-hover]:not([data-hover-bound])'),
    bindHover
  );
}

applyHover(document);
render();

})();
