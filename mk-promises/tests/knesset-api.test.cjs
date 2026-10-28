const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../web/index.html'), 'utf8');
const source = html.slice(html.indexOf('/* ---------- network: native'), html.indexOf('/* ---------- dialogs'));
const BASE = 'https://knesset.gov.il/OdataV4/ParliamentInfo/';
const LIMIT_MESSAGE = "The node count limit of '100' has been exceeded. Set MaxNodeCount on ODataValidationSettings.";

function response(value, status = 200) {
  return {ok: status >= 200 && status < 300, status,
    text: async () => typeof value === 'string' ? value : JSON.stringify(value)};
}
function idsIn(url) {
  return Array.from((url.searchParams.get('$filter') || '').matchAll(/\bId eq (\d+)/g), m => Number(m[1]));
}
function fixture(route, Android) {
  const calls = [], failures = [], messages = [], timers = new Map();
  let uid = 0;
  const ctx = {
    BASE, URL, Promise, TextDecoder, Uint8Array, atob, AbortController,
    window: {Android}, S: {v: 1, mks: []}, bills: Object.create(null),
    uid: () => 'member-' + (++uid),
    toast: value => messages.push(value), save: () => true, render: () => {},
    reportApiError: (action, error, context) => failures.push({action, error, context}),
    fetch: async (url, options) => {calls.push(url);return route(new URL(url), options);},
    setTimeout: (fn, ms) => {const id = setTimeout(fn, ms);timers.set(id, true);return id;},
    clearTimeout: id => {timers.delete(id);clearTimeout(id);},
  };
  vm.createContext(ctx);vm.runInContext(source, ctx);
  return {ctx, calls, failures, messages, timers};
}

test('large OR query reproduces a simulated OData400 and preserves its message without retry', async () => {
  const f = fixture(url => {
    const nodes = 5 * idsIn(url).length - 1;
    return nodes > 100 ? response({error: {code: '', message: LIMIT_MESSAGE}}, 400) : response({value: []});
  });
  const filter = Array.from({length: 30}, (_, i) => 'Id eq ' + (i + 1)).join(' or ');
  await assert.rejects(f.ctx.getJson(BASE + 'KNS_Person?$filter=' + filter), error => {
    assert.equal(error.name, 'KnessetApiError');
    assert.equal(error.status, 400);
    assert.equal(error.entity, 'KNS_Person');
    assert.equal(error.detail, LIMIT_MESSAGE);
    assert.match(error.url, /Id%20eq%201/);
    assert.match(error.responseBody, /MaxNodeCount/);
    return true;
  });
  assert.equal(f.calls.length, 1, '400 errors must not be retried blindly');
  assert.equal(f.timers.size, 0);
});

test('member loading splits 57 IDs into small filters with no more than three active requests', async () => {
  let active = 0, peak = 0, detailCalls = 0;
  const f = fixture(async url => {
    if (url.pathname.endsWith('KNS_PersonToPosition')) {
      return response({value: Array.from({length: 57}, (_, i) => ({PersonID: i + 1, PositionID: i % 2 ? 43 : 61}))});
    }
    const ids = idsIn(url);detailCalls++;
    assert.ok(ids.length > 0 && ids.length <= 10);
    assert.equal(url.searchParams.get('$select'), 'Id,FirstName,LastName');
    if (5 * ids.length - 1 > 100) return response({error: {message: LIMIT_MESSAGE}}, 400);
    active++;peak = Math.max(peak, active);
    await new Promise(resolve => setTimeout(resolve, 3));
    active--;
    return response({value: ids.map(id => ({Id: id, FirstName: 'חבר', LastName: String(id)}))});
  });
  await f.ctx.loadMembers(25);
  assert.equal(f.ctx.S.mks.length, 57);
  assert.equal(detailCalls, 6);
  assert.ok(peak >= 2 && peak <= 3);
  assert.equal(f.failures.length, 0);
  assert.equal(f.timers.size, 0);
});

test('bill loading also uses small filters and keeps the v4 entity fields', async () => {
  let active = 0, peak = 0, detailCalls = 0;
  const f = fixture(async url => {
    if (url.pathname.endsWith('KNS_BillInitiator')) {
      assert.equal(url.searchParams.get('$filter'), 'PersonID eq 5');
      return response({value: Array.from({length: 41}, (_, i) => ({BillID: 100 + i, IsInitiator: true}))});
    }
    const ids = idsIn(url);detailCalls++;
    assert.ok(ids.length > 0 && ids.length <= 10);
    assert.equal(url.searchParams.get('$select'), 'Id,KnessetNum,Name,SubTypeDesc');
    active++;peak = Math.max(peak, active);
    await new Promise(resolve => setTimeout(resolve, 3));active--;
    return response({value: ids.map(id => ({Id: id, KnessetNum: 25, Name: 'הצעת חוק ' + id, SubTypeDesc: 'פרטית'}))});
  });
  const member = {id: 'local', apiPersonId: 5};f.ctx.S.mks.push(member);
  await f.ctx.loadBills(member);
  assert.equal(f.ctx.bills.local.length, 41);
  assert.equal(f.ctx.bills.local[0].BillID, 100);
  assert.equal(f.ctx.bills.local[40].BillID, 140);
  assert.equal(detailCalls, 5);
  assert.ok(peak >= 2 && peak <= 3);
  assert.equal(f.failures.length, 0);
});

test('native bridge decodes the error body before rejecting and cleans pending requests', async () => {
  let nativeId, nativeUrl;
  const f = fixture(null, {httpGet(id, url) {nativeId = id;nativeUrl = url;}});
  const pending = f.ctx.getJson(BASE + 'KNS_Person?%24filter=Id%20eq%201');
  const detail = LIMIT_MESSAGE + ' פרטים מהשרת';
  const body = JSON.stringify({error: {message: {lang: 'en-US', value: detail}}});
  const rejected = assert.rejects(pending, error => {
    assert.equal(error.status, 400);
    assert.equal(error.detail, detail);
    assert.equal(error.url, nativeUrl);
    assert.equal(error.responseBody, body);
    return true;
  });
  f.ctx.window.__nativeDone(nativeId, false, 400, Buffer.from(body).toString('base64'));
  await rejected;
  assert.match(nativeUrl, /\?%24filter=Id%20eq%201$/);
  assert.equal(Object.keys(f.ctx.pending).length, 0);
  assert.equal(f.timers.size, 0);
  f.ctx.window.__nativeDone(nativeId, true, 200, 'e30=');
});

test('schema and authorization errors remain inspectable and are never retried', async () => {
  for (const status of [400, 401, 403]) {
    const detail = status === 400 ? "Could not find a property named 'Unexpected'." : 'Access denied';
    const f = fixture(() => response({error: {message: detail}}, status));
    await assert.rejects(f.ctx.getJson(BASE + 'KNS_Bill?$select=Unexpected'), error => {
      assert.equal(error.status, status);assert.equal(error.detail, detail);
      assert.equal(error.entity, 'KNS_Bill');return true;
    });
    assert.equal(f.calls.length, 1);
  }
});

test('error diagnostics bound untrusted bodies and retain the response without interpreting HTML', () => {
  const f = fixture(() => response({value: []}));
  const htmlBody = '<html><script>unexpected()</script>' + 'x'.repeat(10000);
  const error = f.ctx.makeApiError(400, BASE + 'KNS_PersonToPosition', htmlBody);
  assert.equal(error.responseBody.length, 8192);
  assert.match(error.detail, /HTML/);
  assert.ok(error.detail.length <= 1200);
  assert.equal(error.entity, 'KNS_PersonToPosition');
  const plain = f.ctx.makeApiError(400, BASE + 'KNS_Bill', 'x'.repeat(10000));
  assert.equal(plain.detail.length, 1200);
});

test('bounded workers stop scheduling new chunks after a failed request', async () => {
  const f = fixture(() => response({value: []})), started = [];
  await assert.rejects(f.ctx.mapLimit([0, 1, 2, 3, 4, 5], 3, value => {
    started.push(value);
    return value === 0 ? Promise.reject(new Error('failed')) : Promise.resolve(value);
  }), /failed/);
  await Promise.resolve();
  assert.deepEqual(started, [0, 1, 2]);
});

test('successful HTTP responses with invalid JSON or shape retain entity and body diagnostics', async () => {
  let f = fixture(() => response('<html>Unavailable</html>'));
  await assert.rejects(f.ctx.getJson(BASE + 'KNS_Person'), error => {
    assert.equal(error.status, 200);assert.match(error.detail, /JSON/);
    assert.equal(error.entity, 'KNS_Person');assert.match(error.responseBody, /Unavailable/);return true;
  });
  f = fixture(() => response({unexpected: []}));
  await assert.rejects(f.ctx.getJson(BASE + 'KNS_Bill'), error => {
    assert.equal(error.status, 200);assert.match(error.detail, /מבנה הנתונים/);return true;
  });
});

test('member-stage errors reach diagnostic UI without changing saved data', async () => {
  const f = fixture(() => response({error: {message: LIMIT_MESSAGE}}, 400));
  const existing = {id: 'existing', name: 'שם', apiPersonId: 10, promises: [{id: 'p', text: 'preserved'}], background: []};
  f.ctx.S.mks.push(existing);
  await f.ctx.loadMembers(25);
  assert.equal(f.ctx.S.mks.length, 1);assert.equal(f.ctx.S.mks[0], existing);
  assert.equal(f.failures.length, 1);assert.equal(f.failures[0].action, 'members');
  assert.equal(f.failures[0].error.entity, 'KNS_PersonToPosition');
  assert.equal(f.failures[0].error.status, 400);
});
