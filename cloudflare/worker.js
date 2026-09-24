/**
 * OpenShield Cloudflare Worker
 *
 * Public:
 *   POST /report                    → spam / not_spam oyu
 *   GET  /community-list?since=<ts> → liste güncelleme
 *
 * Admin (/admin?key=SIFREN):
 *   GET    /admin          → web paneli
 *   GET    /admin/stats    → ham JSON
 *   DELETE /admin/delete?hash=<h>
 *   POST   /admin/add      → manuel ekle
 *
 * KV yapısı:
 *   report:<hash> → {
 *     number,        ← düz numara (sadece admin görür)
 *     spamVotes,
 *     notSpamVotes,
 *     lastSeen,
 *     rules,
 *     manual
 *   }
 *
 * Karar: Bayes Ortalaması (Laplace Smoothing):
 *   bayesScore = (spamVotes + 3 * 0.5) / (total + 3)
 *   Şart: manual || (spamVotes >= 2 && bayesScore >= 0.60)
 */

export default {
  async fetch(request, env) {
    const url  = new URL(request.url);
    const cors = {
      "Access-Control-Allow-Origin":  "*",
      "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, X-Admin-Key",
    };

    if (request.method === "OPTIONS") return new Response(null, { headers: cors });

    // ── POST /report ──────────────────────────────────────────────────────────
    if (request.method === "POST" && url.pathname === "/report") {
      try {
        const body = await request.json();

        if (!body.numberHash || typeof body.numberHash !== "string" ||
            body.numberHash.length !== 64) {
          return json({ error: "invalid numberHash" }, 400, cors);
        }

        const voteType = body.voteType === "not_spam" ? "not_spam" : "spam";
        const key      = `report:${body.numberHash}`;
        const existing = await env.OPENSHIELD_KV.get(key, "json") || {};

        const mergedRules = existing.rules || {};
        if (Array.isArray(body.triggeredRules)) {
          for (const rule of body.triggeredRules.slice(0, 15)) {
            if (typeof rule === "string" && rule.length <= 50)
              mergedRules[rule] = (mergedRules[rule] || 0) + 1;
          }
        }

        const updated = {
          number:       body.number || existing.number || null,  // düz numara
          spamVotes:    (existing.spamVotes    || 0) + (voteType === "spam"     ? 1 : 0),
          notSpamVotes: (existing.notSpamVotes || 0) + (voteType === "not_spam" ? 1 : 0),
          lastSeen:     Date.now(),
          rules:        mergedRules,
          manual:       existing.manual || false,
        };

        await env.OPENSHIELD_KV.put(key, JSON.stringify(updated), {
          expirationTtl: 60 * 60 * 24 * 90
        });

        const total       = updated.spamVotes + updated.notSpamVotes;
        const bayesScore  = (updated.spamVotes + 1.5) / (total + 3);
        const inCommunity = updated.manual || (updated.spamVotes >= 2 && bayesScore >= 0.60);

        return json({
          ok:              true,
          voteType,
          spamVotes:       updated.spamVotes,
          notSpamVotes:    updated.notSpamVotes,
          bayesScore:      Math.round(bayesScore * 100),
          inCommunityList: inCommunity,
        }, 200, cors);

      } catch { return json({ error: "bad request" }, 400, cors); }
    }

    // ── GET /community-list ───────────────────────────────────────────────────
    if (request.method === "GET" && url.pathname === "/community-list") {
      const since  = parseInt(url.searchParams.get("since") || "0");
      const listed = await env.OPENSHIELD_KV.list({ prefix: "report:" });
      const result = [];

      for (const kv of listed.keys) {
        const data = await env.OPENSHIELD_KV.get(kv.name, "json");
        if (!data || data.lastSeen <= since) continue;

        const total      = (data.spamVotes || 0) + (data.notSpamVotes || 0);
        const bayesScore = ((data.spamVotes || 0) + 1.5) / (total + 3);
        const inList     = data.manual || ((data.spamVotes || 0) >= 2 && bayesScore >= 0.60);
        if (!inList) continue;

        result.push({
          hash:         kv.name.replace("report:", ""),
          spamVotes:    data.spamVotes    || 0,
          notSpamVotes: data.notSpamVotes || 0,
          bayesScore:   Math.round(bayesScore * 100),
          manual:       data.manual || false,
          topRules:     getTop(data.rules, 5),
        });
        // NOT: number alanı community-list'te gönderilmez — sadece admin görür
      }

      return json(result, 200, cors);
    }

    // ── GET /admin ────────────────────────────────────────────────────────────
    if (request.method === "GET" && url.pathname === "/admin") {
      const key = url.searchParams.get("key") || "";
      if (key !== env.ADMIN_KEY)
        return new Response(loginPage(), { headers: { "Content-Type": "text/html;charset=UTF-8" } });
      return new Response(adminPage(key), { headers: { "Content-Type": "text/html;charset=UTF-8" } });
    }

    // ── GET /admin/stats ──────────────────────────────────────────────────────
    if (request.method === "GET" && url.pathname === "/admin/stats") {
      if (!authOk(request, url, env)) return new Response("Unauthorized", { status: 401 });
      const entries = await getAllEntries(env);
      return json({
        total:       entries.length,
        inList:      entries.filter(e => e.inList).length,
        manualCount: entries.filter(e => e.manual).length,
        entries,
      }, 200, cors);
    }

    // ── DELETE /admin/delete ──────────────────────────────────────────────────
    if (request.method === "DELETE" && url.pathname === "/admin/delete") {
      if (!authOk(request, url, env)) return new Response("Unauthorized", { status: 401 });
      const hash = url.searchParams.get("hash");
      if (!hash) return json({ error: "hash required" }, 400, cors);
      await env.OPENSHIELD_KV.delete(`report:${hash}`);
      return json({ ok: true }, 200, cors);
    }

    // ── POST /admin/add ───────────────────────────────────────────────────────
    if (request.method === "POST" && url.pathname === "/admin/add") {
      if (!authOk(request, url, env)) return new Response("Unauthorized", { status: 401 });
      try {
        const body   = await request.json();
        const number = body.number?.replace(/\D/g, "") || null;
        if (!number) return json({ error: "number gerekli" }, 400, cors);

        const hash = await sha256(number);
        const note = body.note || "Manuel eklendi";

        await env.OPENSHIELD_KV.put(`report:${hash}`, JSON.stringify({
          number,
          spamVotes:    1,
          notSpamVotes: 0,
          lastSeen:     Date.now(),
          rules:        { [note]: 1 },
          manual:       true,
        }), { expirationTtl: 60 * 60 * 24 * 365 });

        return json({ ok: true, hash, number }, 200, cors);
      } catch { return json({ error: "bad request" }, 400, cors); }
    }

    return new Response("Not Found", { status: 404 });
  }
};

// ── Yardımcılar ───────────────────────────────────────────────────────────────

function json(data, status, headers = {}) {
  return new Response(JSON.stringify(data, null, 2), {
    status, headers: { ...headers, "Content-Type": "application/json" }
  });
}

function authOk(request, url, env) {
  return (request.headers.get("X-Admin-Key") || url.searchParams.get("key")) === env.ADMIN_KEY;
}

async function sha256(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, "0")).join("");
}

async function getAllEntries(env) {
  const listed  = await env.OPENSHIELD_KV.list({ prefix: "report:" });
  const entries = [];
  for (const kv of listed.keys) {
    const data = await env.OPENSHIELD_KV.get(kv.name, "json");
    if (!data) continue;
    const total     = (data.spamVotes || 0) + (data.notSpamVotes || 0);
    const spamRatio = total > 0 ? data.spamVotes / total : 0;
    entries.push({
      hash:         kv.name.replace("report:", ""),
      number:       data.number || null,   // düz numara — sadece admin görür
      spamVotes:    data.spamVotes    || 0,
      notSpamVotes: data.notSpamVotes || 0,
      spamRatio:    Math.round(spamRatio * 100),
      lastSeen:     new Date(data.lastSeen).toISOString(),
      topRules:     getTop(data.rules, 10),
      manual:       data.manual || false,
      inList:       data.manual || spamRatio > 0.5,
    });
  }
  return entries.sort((a, b) => b.spamVotes - a.spamVotes);
}

function getTop(obj, n) {
  if (!obj) return [];
  return Object.entries(obj).sort(([,a],[,b]) => b-a).slice(0,n).map(([key,count])=>({key,count}));
}

// ── HTML ──────────────────────────────────────────────────────────────────────

function loginPage() {
  return `<!DOCTYPE html><html lang="tr">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>OpenShield Admin</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#080D18;color:#F8FAFC;font-family:system-ui,sans-serif;
     display:flex;align-items:center;justify-content:center;min-height:100vh}
.card{background:#0F1623;border:1px solid #1C2840;border-radius:16px;padding:40px;width:360px}
h2{color:#3B82F6;margin-bottom:8px}
p{color:#94A3B8;font-size:14px;margin-bottom:24px}
input{width:100%;background:#1C2840;border:1px solid #334155;border-radius:10px;
      padding:12px 16px;color:#F8FAFC;font-size:15px;outline:none;margin-bottom:16px}
input:focus{border-color:#3B82F6}
button{width:100%;background:#3B82F6;color:#fff;border:none;border-radius:10px;
       padding:13px;font-size:15px;font-weight:600;cursor:pointer}
button:hover{background:#2563EB}
</style></head>
<body>
<div class="card">
  <h2>🛡 OpenShield</h2>
  <p>Admin paneline erişmek için anahtar girin.</p>
  <input type="password" id="k" placeholder="Admin anahtarı"
         onkeydown="if(event.key==='Enter')login()">
  <button onclick="login()">Giriş</button>
</div>
<script>
function login(){
  const k=document.getElementById('k').value.trim();
  if(k) window.location.href='/admin?key='+encodeURIComponent(k);
}
</script>
</body></html>`;
}

function adminPage(adminKey) {
  return `<!DOCTYPE html><html lang="tr">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>OpenShield Admin</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#080D18;color:#F8FAFC;font-family:system-ui,sans-serif;padding:24px}
h1{color:#3B82F6;font-size:22px;margin-bottom:4px}
.sub{color:#94A3B8;font-size:13px;margin-bottom:20px}
.stats{display:flex;gap:12px;margin-bottom:20px;flex-wrap:wrap}
.stat{background:#0F1623;border:1px solid #1C2840;border-radius:12px;padding:14px 20px}
.stat-val{font-size:26px;font-weight:700;color:#3B82F6}
.stat-lbl{font-size:11px;color:#94A3B8;margin-top:2px}
.toolbar{display:flex;gap:10px;margin-bottom:14px;flex-wrap:wrap;align-items:center}
.btn{border:none;border-radius:8px;padding:8px 16px;font-size:13px;cursor:pointer;font-weight:600}
.btn-blue{background:#3B82F6;color:#fff}.btn-blue:hover{background:#2563EB}
.btn-green{background:#22C55E;color:#000}.btn-green:hover{background:#16A34A}
.add-box{background:#0F1623;border:1px solid #1C2840;border-radius:12px;
         padding:16px;margin-bottom:16px;display:none}
.add-box.open{display:block}
.add-box h3{color:#22C55E;font-size:13px;margin-bottom:10px}
.row{display:flex;gap:8px;flex-wrap:wrap}
.inp{background:#1C2840;border:1px solid #334155;border-radius:8px;
     padding:8px 12px;color:#F8FAFC;font-size:13px;outline:none;flex:1;min-width:160px}
.inp:focus{border-color:#22C55E}
table{width:100%;border-collapse:collapse;font-size:13px}
th{background:#1C2840;color:#94A3B8;font-size:11px;text-align:left;
   padding:8px 12px;font-weight:600}
td{padding:8px 12px;border-top:1px solid #0F1623;vertical-align:middle}
tr:hover td{background:#0F1623}
.badge{display:inline-block;padding:2px 7px;border-radius:5px;font-size:11px;font-weight:600}
.b-spam{background:#EF444420;color:#EF4444}
.b-clean{background:#22C55E20;color:#22C55E}
.b-manual{background:#F59E0B20;color:#F59E0B}
.b-wait{background:#47556920;color:#94A3B8}
.num{font-family:monospace;font-size:12px;color:#F8FAFC}
.hash{font-family:monospace;font-size:10px;color:#334155}
.ratio-bar{width:60px;height:5px;background:#1C2840;border-radius:3px;
           overflow:hidden;display:inline-block;vertical-align:middle;margin-left:6px}
.ratio-fill{height:100%;background:#EF4444;border-radius:3px}
.del{background:transparent;color:#EF4444;border:1px solid #EF444440;
     border-radius:5px;padding:3px 8px;font-size:11px;cursor:pointer}
.del:hover{background:#EF444415}
.empty{text-align:center;padding:40px;color:#475569}
#st{font-size:12px;margin-left:8px}
.ok-c{color:#22C55E}.err-c{color:#EF4444}
</style></head>
<body>
<h1>🛡 OpenShield — Topluluk Verileri</h1>
<div class="sub">Cloudflare KV · Oran bazlı oylama · Numaralar sadece burada görünür</div>

<div class="stats">
  <div class="stat"><div class="stat-val" id="s-total">—</div><div class="stat-lbl">Toplam</div></div>
  <div class="stat"><div class="stat-val" id="s-list" style="color:#EF4444">—</div><div class="stat-lbl">Listede</div></div>
  <div class="stat"><div class="stat-val" id="s-manual" style="color:#F59E0B">—</div><div class="stat-lbl">Manuel</div></div>
</div>

<div class="toolbar">
  <button class="btn btn-blue" onclick="load()">🔄 Yenile</button>
  <button class="btn btn-green" onclick="toggleAdd()">➕ Manuel Ekle</button>
  <span id="st"></span>
</div>

<div class="add-box" id="addBox">
  <h3>➕ Manuel Numara Ekle</h3>
  <div class="row">
    <input class="inp" type="text" id="addNum" placeholder="Numara (örn: 905551234567)">
    <input class="inp" type="text" id="addNote" placeholder="Not (örn: Bahis spam)">
    <button class="btn btn-green" onclick="addEntry()">Ekle</button>
  </div>
</div>

<table>
  <thead><tr>
    <th>Numara</th>
    <th>Hash (kısaltılmış)</th>
    <th>Spam</th>
    <th>Değil</th>
    <th>Oran</th>
    <th>Son Görülme</th>
    <th>Kurallar</th>
    <th>Durum</th>
    <th></th>
  </tr></thead>
  <tbody id="tbody">
    <tr><td colspan="9" class="empty">Yükleniyor...</td></tr>
  </tbody>
</table>

<script>
const KEY = ${JSON.stringify(adminKey)};

function st(msg, ok) {
  const el = document.getElementById('st');
  el.textContent = msg;
  el.className = ok ? 'ok-c' : 'err-c';
  setTimeout(() => el.textContent = '', 3000);
}

function toggleAdd() {
  document.getElementById('addBox').classList.toggle('open');
}

async function addEntry() {
  const num  = document.getElementById('addNum').value.trim();
  const note = document.getElementById('addNote').value.trim() || 'Manuel eklendi';
  if (!num) { st('Numara girin', false); return; }

  const res = await fetch('/admin/add', {
    method:  'POST',
    headers: { 'Content-Type': 'application/json', 'X-Admin-Key': KEY },
    body:    JSON.stringify({ number: num, note })
  });
  const d = await res.json();
  if (res.ok) {
    st('✓ Eklendi: ' + d.number, true);
    document.getElementById('addNum').value  = '';
    document.getElementById('addNote').value = '';
    load();
  } else {
    st('Hata: ' + (d.error || res.status), false);
  }
}

async function load() {
  const res = await fetch('/admin/stats', { headers: { 'X-Admin-Key': KEY } });
  if (!res.ok) { st('Yetki hatası', false); return; }
  const d = await res.json();

  document.getElementById('s-total').textContent  = d.total;
  document.getElementById('s-list').textContent   = d.inList;
  document.getElementById('s-manual').textContent = d.manualCount;

  const tbody = document.getElementById('tbody');
  if (!d.entries.length) {
    tbody.innerHTML = '<tr><td colspan="9" class="empty">Henüz veri yok.</td></tr>';
    return;
  }

  tbody.innerHTML = d.entries.map(e => {
    const numDisplay  = e.number
      ? \`<span class="num">\${e.number}</span>\`
      : '<span style="color:#475569;font-size:11px">—</span>';
    const hashShort   = e.hash.substring(0, 12) + '…';
    const rules       = e.topRules.slice(0, 3).map(r => r.key).join(', ') || '—';
    const date        = new Date(e.lastSeen).toLocaleString('tr-TR');
    const badge       = e.manual
      ? '<span class="badge b-manual">Manuel</span>'
      : e.inList
        ? '<span class="badge b-spam">Spam</span>'
        : e.spamVotes > 0
          ? '<span class="badge b-wait">Bekliyor</span>'
          : '<span class="badge b-clean">Temiz</span>';

    return \`<tr>
      <td>\${numDisplay}</td>
      <td><span class="hash" title="\${e.hash}">\${hashShort}</span></td>
      <td style="color:#EF4444;font-weight:700">\${e.spamVotes}</td>
      <td style="color:#22C55E">\${e.notSpamVotes}</td>
      <td>
        %\${e.spamRatio}
        <span class="ratio-bar">
          <span class="ratio-fill" style="width:\${e.spamRatio}%"></span>
        </span>
      </td>
      <td style="color:#94A3B8;font-size:11px">\${date}</td>
      <td style="color:#94A3B8;font-size:11px">\${rules}</td>
      <td>\${badge}</td>
      <td><button class="del" onclick="del('\${e.hash}')">Sil</button></td>
    </tr>\`;
  }).join('');
}

async function del(hash) {
  if (!confirm('Silmek istediğine emin misin?')) return;
  const res = await fetch('/admin/delete?hash=' + hash, {
    method: 'DELETE', headers: { 'X-Admin-Key': KEY }
  });
  if (res.ok) { st('✓ Silindi', true); load(); }
}

load();
</script>
</body></html>`;
}
