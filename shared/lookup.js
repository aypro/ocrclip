const box = document.getElementById('text');
const backChip = document.getElementById('back'); // second screen only
if (backChip) backChip.onclick = () => back();
const results = document.getElementById('results');
const chars = []; // [UTF-16 offset, span], so offsets match Kotlin's String indices
for (let i = 0; i < text.length;) {
    const ch = String.fromCodePoint(text.codePointAt(i));
    if (ch === '\n') {
        box.appendChild(document.createElement('br'));
        i++;
        continue;
    }
    const span = document.createElement('span');
    span.textContent = ch;
    const at = i;
    span.onclick = () => go(at);
    box.appendChild(span);
    chars.push([i, span]);
    i += ch.length;
}
let pos = 0, len = 0; // the word picked in the captured text
// What the results show: {} for the captured-text word, {q, r} for a followed link. The history is what B returns to
// (OcrService calls back()), or the ‹ chip on the second screen; picking a character starts over.
let current = {};
const history = [];
let renders = 0;
function render(state) {
    const onText = state.q === undefined;
    const r = JSON.parse(onText ? OCRClip.lookup(text, pos) : OCRClip.lookupTerm(state.q, state.r));
    if (onText) len = r.length;
    for (const [i, span] of chars) {
        span.className = !onText ? '' : i === pos ? 'match cursor' : i > pos && i < pos + len ? 'match' : '';
    }
    current = state;
    results.innerHTML = r.html;
    if (onText) {
        // A long capture scrolls inside its own box (it would otherwise cover the results); keep the cursor in view.
        const cursor = chars.find(([i]) => i === pos);
        if (cursor) cursor[1].scrollIntoView({ block: 'nearest' });
    }
    window.scrollTo(0, 0);
    OCRClip.setHistory(history.length);
    if (backChip) backChip.classList.toggle('on', history.length > 0);
    const n = ++renders;
    // Asking AnkiDroid can take a moment, so the entries show first and get their marks after.
    setTimeout(() => {
        if (n !== renders || !results.querySelector('.add')) return;
        for (const e of JSON.parse(OCRClip.ankiExisting())) markAdded(e);
    }, 0);
}
// A short message over the page: the service's toasts don't show when OCR Clip's notifications are off.
const notice = document.createElement('div');
notice.id = 'notice';
document.body.appendChild(notice);
function showNotice(message) {
    notice.textContent = message;
    notice.classList.add('on');
    clearTimeout(notice.hide);
    notice.hide = setTimeout(() => notice.classList.remove('on'), 2500);
}
// An entry already in Anki: its + buttons become ✓, and adding another note takes a second tap.
function markAdded(e) {
    for (const b of results.querySelectorAll('.add[data-e="' + e + '"]')) {
        if (b.classList.contains('done')) continue;
        b.classList.add('dup');
        b.textContent = '✓';
    }
}
function go(offset) {
    pos = offset;
    history.length = 0;
    render({});
}
function follow(q, r) {
    history.push(current);
    render({ q, r });
}
function back() {
    if (history.length) render(history.pop());
}
results.addEventListener('click', (e) => {
    const add = e.target.closest('.add');
    if (add) {
        if (add.classList.contains('busy') || add.classList.contains('done')) return;
        if (add.classList.contains('dup') && !add.classList.contains('armed')) {
            add.classList.add('armed');
            add.textContent = '+?';
            showNotice('Already in Anki. Tap again to add another');
            clearTimeout(add.disarm);
            add.disarm = setTimeout(() => {
                add.classList.remove('armed');
                if (add.classList.contains('dup')) add.textContent = '✓';
            }, 3000);
            return;
        }
        clearTimeout(add.disarm);
        add.classList.remove('dup', 'armed');
        add.classList.add('busy');
        add.textContent = '…';
        const offset = current.q === undefined ? pos : -1; // where the word is in the captured text
        // Let the dots paint before the bridge call holds up the page.
        setTimeout(() => {
            const sense = add.dataset.s === undefined ? -1 : +add.dataset.s; // -1: the whole definition
            const added = JSON.parse(OCRClip.addToAnki(+add.dataset.e, +add.dataset.d, sense, offset));
            add.classList.remove('busy');
            if (added.error) {
                add.textContent = '+';
                showNotice(added.error);
            } else {
                add.classList.add('done');
                add.textContent = '✓';
                markAdded(add.dataset.e);
                if (added.notice) showNotice(added.notice);
            }
        }, 30);
        return;
    }
    const link = e.target.closest('.link[data-q]');
    if (link) {
        follow(link.dataset.q, link.dataset.r);
        return;
    }
    const scanned = scanAt(e.clientX, e.clientY);
    if (scanned) follow(scanned, '');
});
// Other Japanese text in the entries is looked up from the tapped character, like Yomitan's scan: the run of Japanese
// from there, up to 16 characters (LocalDictionary.MAX_LENGTH), read across inline elements without furigana. The
// longest match is found by lookupTerm. Same characters as LocalDictionary.isJapanese.
const JAPANESE = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}ーｰ゛゜ﾞﾟ０-９Ａ-Ｚａ-ｚ]/u;
const SKIP = 'rt, rp, .add, .tag, .freq';
function charRect(node, offset) {
    const range = document.createRange();
    range.setStart(node, offset);
    range.setEnd(node, Math.min(node.data.length, offset + 1));
    return range.getBoundingClientRect();
}
function scanAt(x, y) {
    const caret = document.caretRangeFromPoint(x, y);
    if (!caret) return '';
    const node = caret.startContainer;
    if (node.nodeType !== Node.TEXT_NODE || !results.contains(node) || node.parentElement.closest(SKIP)) return '';
    // The caret lands after a character tapped on its right half; a tap that misses both is on blank space. A
    // character under the finger wins over one only within the slop.
    const candidates = [caret.startOffset, caret.startOffset - 1].filter((o) => o >= 0 && o < node.data.length);
    const hits = (o, slop) => {
        const r = charRect(node, o);
        return r.width > 0 && x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop;
    };
    const offset = candidates.find((o) => hits(o, 0)) ?? candidates.find((o) => hits(o, 4)) ?? -1;
    if (offset < 0) return '';
    let text = '';
    const root = node.parentElement.closest('li, td, th, div') || results;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    walker.currentNode = node;
    for (let n = node, start = offset; n; n = walker.nextNode(), start = 0) {
        if (n !== node && n.parentElement.closest(SKIP)) continue;
        for (const ch of n.data.substring(start)) {
            if (!JAPANESE.test(ch) || text.length >= 16) return text;
            text += ch;
        }
    }
    return text;
}
document.addEventListener('keydown', (e) => {
    const k = chars.findIndex(([i]) => i === pos);
    if (e.key === 'ArrowRight') {
        const next = chars.find(([i]) => i >= pos + Math.max(len, 1));
        if (next) go(next[0]);
    } else if (e.key === 'ArrowLeft') {
        if (k > 0) go(chars[k - 1][0]);
    } else {
        return;
    }
    e.preventDefault();
});
if (chars.length) go(chars[0][0]);
