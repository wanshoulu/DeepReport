/*
 * gen-snapshots.js — 第 6 轮多银行扩展：生成 cmb/icbc/ccb 三套快照与工件（含 charts）
 *
 * 用法：node gen-snapshots.js   （在 deploy/ 目录下运行）
 * 产物：
 *   snapshot/cmb/{manifest.json, pages/, positive-artifact.json, error-artifact.json}
 *   snapshot/icbc/... 、 snapshot/ccb/...
 *   demo/{positive-artifact.json, error-artifact.json}   （CMB，兼容 workbench boot）
 *
 * 纪律：
 *   1) CMB 页文本逐字节复制现有文件 → digest 稳定（696460ed…/69c60965…/各页 digest 不变）
 *   2) icbc/ccb 按同构布局生成；P18/P30/P37 不得含「2024年」；P46 首句含「2024年」
 *   3) 工件含顶层 charts（List<Map>），checker 不校验 charts
 *   4) 脚本内置自检：N4 派生复算、N6 同比复算、pie 值页内字面、CMB digest 对照
 */

'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const DEPLOY = __dirname;
const SNAP_ROOT = path.join(DEPLOY, 'snapshot');
const DEMO_DIR = path.join(DEPLOY, 'demo');
const GENERATED_AT = '2026-11-16';

const { ICBC, CCB } = require('./bank-pages.js');

// ——————— 0. 骨架配置（0.3.4 泛化）：标题/节序/静态节正文/责任 agent 唯一事实源是 report-structure.json ———————
// 方法约定（名称保留、深度收缩）沉淀在 skill references；银行差异（3.x/8.x/6.x 章号）只是配置数据。
const STRUCTURE = JSON.parse(fs.readFileSync(path.join(DEPLOY, 'report-structure.json'), 'utf8'));

function titleOf(bank, sectionId) {
  const bankCfg = STRUCTURE.banks[bank];
  if (!bankCfg || !bankCfg.titles || !bankCfg.titles[sectionId]) {
    throw new Error('report-structure.json 缺少标题配置: banks.' + bank + '.titles.' + sectionId);
  }
  return bankCfg.titles[sectionId];
}

function fillBody(tpl, manifest, company) {
  const primary = (manifest.sources[0].pages || []).map((p) => 'P' + p).join('/');
  const comparison = (manifest.sources[1].pages || []).map((p) => 'P' + p).join('/');
  return String(tpl)
    .replace(/\{company\}/g, company)
    .replace(/\{snapshot_version\}/g, manifest.snapshot_version)
    .replace(/\{primary_pages\}/g, primary)
    .replace(/\{comparison_pages\}/g, comparison);
}

function staticSectionsOf(b, manifest) {
  return STRUCTURE.section_order
    .filter((sid) => STRUCTURE.sections[sid] && STRUCTURE.sections[sid].role === 'static')
    .map((sid) => ({
      section_id: sid,
      title: titleOf(b.bank, sid),
      body: fillBody(STRUCTURE.static_bodies[sid], manifest, b.company),
      claim_ids: [],
    }));
}

// 按配置归一化工件骨架：标题取配置、静态节体取配置、按 section_order 排序补缺
function normalizeSections(art, manifest, bank) {
  const byId = new Map(art.sections.map((s) => [s.section_id, s]));
  art.sections = STRUCTURE.section_order.map((sid) => {
    const cfg = STRUCTURE.sections[sid];
    if (!byId.has(sid)) {
      if (cfg.role !== 'static') throw new Error('analysis 节缺失素材: ' + sid);
      return { section_id: sid, title: titleOf(bank, sid), body: fillBody(STRUCTURE.static_bodies[sid], manifest, art.company), claim_ids: [] };
    }
    const sec = byId.get(sid);
    sec.title = titleOf(bank, sid);
    if (cfg.role === 'static') sec.body = fillBody(STRUCTURE.static_bodies[sid], manifest, art.company);
    return sec;
  });
  return art;
}

function sha256(text) {
  return crypto.createHash('sha256').update(text, 'utf8').digest('hex');
}
function round2(x) { return Math.round(x * 100) / 100; }
function fmt(v) { return v.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ','); }
function fmtDelta(d, dp) { return (d >= 0 ? '+' : '-') + Math.abs(d).toFixed(dp); }

// 契约白名单（Jackson FAIL_ON_UNKNOWN_PROPERTIES=true，多余/缺失字段即拒载）
const METRIC_KEYS = ['key', 'value', 'unit', 'period', 'source_document_digest', 'page', 'evidence_excerpt_digest', 'yoy_delta', 'delta_unit'];
const CITATION_KEYS = ['claim_id', 'claim_text', 'source_document_digest', 'page', 'evidence_excerpt', 'evidence_excerpt_digest'];

// ——————— 1. CMB 快照：逐字节复制现有文件，manifest 顶层补 bank 字段（digest 值不变） ———————
const CMB_DIGESTS = {
  primary: '696460edb9ddc740f83c8baf8a26d69ebf050f252f65442e871c0dbe818dd39f',
  comparison: '69c6096504be57484a32ea1afb02499891286066ea1179dcb30f54212bec3366',
  pages: {
    '18': '0637825d9c9a90511caf358e05686a64fdc086cd98eb38150f0be9aee173acce',
    '30': 'f066fe7f5cd0278130e9afd7e23f82ce37cd55fc4a1d9eb7f881e6e00b565065',
    '37': 'fadb64dd4b4eae13d7e34f430c58da5844a5e4e577cd5bb4e5546db83b14284d',
    '46': '69c6096504be57484a32ea1afb02499891286066ea1179dcb30f54212bec3366',
  },
};

function copyDir(src, dest) {
  fs.mkdirSync(dest, { recursive: true });
  for (const name of fs.readdirSync(src)) {
    const s = path.join(src, name);
    const d = path.join(dest, name);
    if (fs.statSync(s).isDirectory()) copyDir(s, d);
    else fs.copyFileSync(s, d);
  }
}

const LEGACY_SNAP = path.join(SNAP_ROOT, 'manifest.json'); // 现有单银行布局
const LEGACY_PAGES = path.join(SNAP_ROOT, 'pages');
const CMB_DIR = path.join(SNAP_ROOT, 'cmb');

if (fs.existsSync(LEGACY_SNAP)) {
  copyDir(LEGACY_PAGES, path.join(CMB_DIR, 'pages'));
  const manifest = JSON.parse(fs.readFileSync(LEGACY_SNAP, 'utf8'));
  manifest.bank = 'cmb';
  fs.writeFileSync(path.join(CMB_DIR, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n', 'utf8');
  console.log('[cmb] manifest+pages copied from legacy layout');
} else {
  console.log('[cmb] legacy layout absent — assume cmb/ already generated, skip copy');
}

// ——————— 2. 页文本 → digest → manifest（icbc/ccb） ———————
function pageText(lines) { return lines.join('\n') + '\n'; }

function buildManifest(b) {
  const digests = {};
  for (const p of b.pages) digests[String(p)] = sha256(pageText(b['pages_' + p]));
  const primary = sha256(pageText(b.pages_18) + pageText(b.pages_30) + pageText(b.pages_37));
  const comparison = digests['46'];
  const short = b.bank;
  return {
    snapshot_version: short + '-ar-2025-w01@1.0.0',
    bank: b.bank,
    company: b.company,
    report_period: b.reportPeriod,
    generated_at: GENERATED_AT,
    source_note: b.sourceNote,
    sources: [
      {
        source_id: short + '-ar-2025-primary',
        title: b.company + '2025年度报告（本期节选·演示口径）',
        authority: 'PRIMARY_OFFICIAL',
        origin_url: 'snapshot:local/' + short + '-ar-2025-primary（公开披露年报落盘快照，不实时访问）',
        fetched_at: GENERATED_AT,
        content_sha256: primary,
        pages: [18, 30, 37],
        note: '本期年报节选：总体经营（P18）、贷款质量（P30）、资本充足（P37）。业务及管理费与贷款减值准备为按披露比率复算的整理值，其余数值均为页内原文。',
      },
      {
        source_id: short + '-ar-2024-comparison',
        title: b.company + '2024年度报告（对比基数节选）',
        authority: 'PRIMARY_OFFICIAL',
        origin_url: 'snapshot:local/' + short + '-ar-2024-comparison（公开披露年报落盘快照，不实时访问）',
        fetched_at: GENERATED_AT,
        content_sha256: comparison,
        pages: [46],
        note: '上年度对比基数页：营收/净利/净息差/成本收入比/不良余额/不良率/拨备覆盖率/CET1。',
      },
    ],
    page_digests: digests,
    entities: [
      { full_name: b.company, short_names: ['本集团', '本行'] },
      { full_name: '国家金融监督管理总局', short_names: ['监管机构'] },
    ],
    metric_index: {
      OPERATING_REVENUE: { label: '营业收入', page: 18, source_id: short + '-ar-2025-primary' },
      NET_PROFIT_ATTRIBUTABLE: { label: '归属于本行股东的净利润', page: 18, source_id: short + '-ar-2025-primary' },
      NET_INTEREST_MARGIN: { label: '净息差', page: 18, source_id: short + '-ar-2025-primary' },
      COST_INCOME_RATIO: { label: '成本收入比', page: 18, source_id: short + '-ar-2025-primary' },
      NPL_BALANCE: { label: '不良贷款余额', page: 30, source_id: short + '-ar-2025-primary' },
      NPL_RATIO: { label: '不良贷款率', page: 30, source_id: short + '-ar-2025-primary' },
      PROVISION_COVERAGE: { label: '拨备覆盖率', page: 30, source_id: short + '-ar-2025-primary' },
      CET1_RATIO: { label: '核心一级资本充足率', page: 37, source_id: short + '-ar-2025-primary' },
    },
  };
}

// ——————— 3. 工件生成（metrics + citations + sections + charts） ———————
function buildMetrics(b, manifest) {
  const primary = manifest.sources[0].content_sha256;
  // MetricValue 契约 9 字段：value 为千分位原文口径字符串、unit 中文、yoy_delta 带符号字符串；
  // evidence_excerpt_digest 取对应引用摘录的 sha256（与 cmb 正例工件同构）。
  const M = (key, value, unit, delta, deltaUnit, dp, page, excerpt, digest) => ({
    key, value, unit, period: b.reportPeriod,
    source_document_digest: digest, page,
    evidence_excerpt_digest: sha256(excerpt),
    yoy_delta: fmtDelta(delta, dp), delta_unit: deltaUnit,
  });
  const t = b.metricsText;
  return [
    M('OPERATING_REVENUE', fmt(t.rev), '亿元', t.revDelta, 'PERCENT', 2, 18, t.revExcerpt, primary),
    M('NET_PROFIT_ATTRIBUTABLE', fmt(t.profit), '亿元', t.profitDelta, 'PERCENT', 2, 18, t.profitExcerpt, primary),
    M('NET_INTEREST_MARGIN', fmt(t.nim), '%', t.nimDelta, 'BASIS_POINT', 0, 18, t.nimExcerpt, primary),
    M('COST_INCOME_RATIO', fmt(t.cir), '%', t.cirDelta, 'PERCENTAGE_POINT', 2, 18, t.cirExcerpt, primary),
    M('NPL_BALANCE', fmt(t.npl), '亿元', t.nplDelta, 'YUAN', 2, 30, t.nplExcerpt, primary),
    M('NPL_RATIO', fmt(t.nplr), '%', t.nplrDelta, 'PERCENTAGE_POINT', 2, 30, t.nplrExcerpt, primary),
    M('PROVISION_COVERAGE', fmt(t.prov), '%', t.provDelta, 'PERCENTAGE_POINT', 2, 30, t.provExcerpt, primary),
    M('CET1_RATIO', fmt(t.cet1), '%', t.cet1Delta, 'PERCENTAGE_POINT', 2, 37, t.cet1Excerpt, primary),
  ];
}

function buildArtifact(b, manifest, taskId, opts) {
  // opts.corruptS2: 将 S-2 拨备短语改为「%」触发 N7（error 件）
  const short = b.bank;
  const metrics = buildMetrics(b, manifest);
  const citations = [];
  const sections = [];
  const sectionOfClaim = {}; // claim_id → section_id（仅生成期路由用，不进工件 JSON）
  let claimSeq = 0;
  const claimIdOf = () => 'C-' + (++claimSeq);

  for (const sec of b.sections) {
    let body = sec.body;
    let claims = sec.claims;
    if (opts && opts.corruptS2 && sec.section_id === 'S-2') {
      body = body.replace('个百分点', '%');
      claims = claims.map((c) => ({
        claim_text: c.claim_text.replace('个百分点', '%'),
        evidence_excerpt: c.evidence_excerpt,
      }));
    }
    const claimIds = [];
    for (const c of claims) {
      const id = claimIdOf();
      claimIds.push(id);
      sectionOfClaim[id] = sec.section_id;
      citations.push({
        claim_id: id, claim_text: c.claim_text,
        source_document_digest: manifest.sources[0].content_sha256,
        page: sec.page || 18,
        evidence_excerpt: c.evidence_excerpt,
        evidence_excerpt_digest: sha256(c.evidence_excerpt),
      });
    }
    sections.push({ section_id: sec.section_id, title: titleOf(short, sec.section_id), body, claim_ids: claimIds });
  }
  // S-7 对比节引用上年页（comparison digest, page 46）
  for (const c of citations) if (sectionOfClaim[c.claim_id] === 'S-7') { c.source_document_digest = manifest.sources[1].content_sha256; c.page = 46; }
  // S-2 与 S-6 中部分 claim 落在 P30（不良余额/不良率/拨备/减值准备/贷款总额）
  const p30ClaimTexts = ['不良贷款余额', '不良贷款率', '拨备覆盖率', '贷款减值准备', '贷款和垫款本金总额', '贷款拨备率'];
  for (const c of citations) {
    if (sectionOfClaim[c.claim_id] === 'S-2' && p30ClaimTexts.some((k) => c.claim_text.startsWith(k))) { c.page = 30; }
  }
  // S-5 资本 → P37
  for (const c of citations) if (sectionOfClaim[c.claim_id] === 'S-5') c.page = 37;

  sections.push(...staticSectionsOf(b, manifest));

  const art = {
    task_id: taskId,
    company: b.company,
    report_period: b.reportPeriod,
    generated_at: GENERATED_AT + 'T00:00:00Z',
    metrics,
    citations,
    sections,
    charts: buildCharts(b),
  };
  normalizeSections(art, manifest, b.bank);
  return art;
}

// ——————— 4. 图表（顶层 charts，前端内联 SVG 渲染） ———————
function buildCharts(b) {
  const t = b.chartsText;
  return [
    {
      id: 'CH-1', type: 'bar', section_id: 'S-1',
      title: '营业收入与归母净利润：本期 vs 上年', unit: '亿元',
      labels: ['上年(2024)', '本期(2025)'],
      series: [
        { name: '营业收入', values: t.barRev },
        { name: '归母净利润', values: t.barProfit },
      ],
    },
    {
      id: 'CH-2', type: 'line', section_id: 'S-3',
      title: '净息差与成本收入比走势', unit: '%',
      labels: ['上年(2024)', '本期(2025)'],
      series: [
        { name: '净息差', values: t.lineNim },
        { name: '成本收入比', values: t.lineCir },
      ],
    },
    {
      id: 'CH-3', type: 'pie', section_id: 'S-6',
      title: '营业收入结构：利息 vs 非利息', unit: '亿元',
      series: [
        { name: '净利息收入', values: [t.pieInterest] },
        { name: '非利息收入', values: [t.pieNonInterest] },
      ],
    },
    {
      id: 'CH-4', type: 'flow', section_id: 'S-9',
      title: '受控生成流水线',
      nodes: ['快照装载', '指标提取', '分节成稿', '数值核验', '引用核验', '具名批准', '受控交付'],
    },
  ];
}

// ——————— 5. 银行数据包（metrics/charts 取值文本） ———————
const BANKS = [
  {
    ...ICBC,
    dir: 'icbc', taskIdPos: 'TASK-2025-AR-003', taskIdErr: 'TASK-2025-AR-005',
    metricsText: {
      rev: 8218.03, revDelta: -2.52, revExcerpt: '营业收入8,218.03亿元，同比下降2.52%',
      profit: 3658.63, profitDelta: 0.51, profitExcerpt: '净利润3,658.63亿元，同比增长0.51%',
      nim: 1.42, nimDelta: -19, nimExcerpt: '净息差1.42%，同比下降19个基点',
      cir: 28.04, cirDelta: 1.08, cirExcerpt: '成本收入比28.04%，同比上升1.08个百分点',
      npl: 3794.58, nplDelta: 259.56, nplExcerpt: '不良贷款余额3,794.58亿元，较上年末增加259.56亿元',
      nplr: 1.34, nplrDelta: -0.02, nplrExcerpt: '不良贷款率1.34%，较上年末下降0.02个百分点',
      prov: 214.91, provDelta: 0.94, provExcerpt: '拨备覆盖率214.91%，较上年末上升0.94个百分点',
      cet1: 14.10, cet1Delta: 0.38, cet1Excerpt: '核心一级资本充足率14.10%',
    },
    chartsText: {
      barRev: [8430.70, 8218.03], barProfit: [3639.93, 3658.63],
      lineNim: [1.61, 1.42], lineCir: [26.96, 28.04],
      pieInterest: 6372.05, pieNonInterest: 1845.98,
    },
  },
  {
    ...CCB,
    dir: 'ccb', taskIdPos: 'TASK-2025-AR-004', taskIdErr: 'TASK-2025-AR-006',
    sourceNote: '真实披露页素材取自建设银行官网《2025年半年度报告》公开 PDF（P2-P75 节选，共 70 页，按 PDF 物理页码落盘）；P18/P30/P37/P46 数值演示页沿用 2024 年报披露口径充当 2025 期（见工件 S-10 标注）。',
    metricsText: {
      rev: 7501.51, revDelta: -2.54, revExcerpt: '营业收入7,501.51亿元，较上年减少195.85亿元，降幅2.54%',
      profit: 3355.77, profitDelta: 0.88, profitExcerpt: '净利润3,355.77亿元，较上年增加29.24亿元，增幅0.88%',
      nim: 1.51, nimDelta: -19, nimExcerpt: '净息差1.51%，同比下降19个基点',
      cir: 29.44, cirDelta: 1.24, cirExcerpt: '成本收入比29.44%，同比上升1.24个百分点',
      npl: 3446.91, nplDelta: 194.35, nplExcerpt: '不良贷款余额3,446.91亿元，较上年末增加194.35亿元',
      nplr: 1.34, nplrDelta: -0.03, nplrExcerpt: '不良贷款率1.34%，较上年末下降0.03个百分点',
      prov: 233.60, provDelta: -6.25, provExcerpt: '拨备覆盖率233.60%，较上年末下降6.25个百分点',
      cet1: 14.48, cet1Delta: 1.33, cet1Excerpt: '核心一级资本充足率14.48%',
    },
    chartsText: {
      barRev: [7697.36, 7501.51], barProfit: [3326.53, 3355.77],
      lineNim: [1.70, 1.51], lineCir: [28.20, 29.44],
      pieInterest: 5898.82, pieNonInterest: 1602.69,
    },
  },
];

module.exports = { BANKS, buildManifest, buildArtifact, pageText, sha256, CMB_DIR, CMB_DIGESTS, SNAP_ROOT, DEMO_DIR, GENERATED_AT, copyDir };

// ——————— 6. 执行：生成目录/工件 + 自检 ———————
const failures = [];

function check(name, ok, detail) {
  if (!ok) failures.push(name + ' :: ' + detail);
  console.log((ok ? '  PASS ' : '  FAIL ') + name + (detail ? ' — ' + detail : ''));
}

for (const b of BANKS) {
  console.log('\n== ' + b.bank.toUpperCase() + ' ' + b.company + ' ==');
  const dir = path.join(SNAP_ROOT, b.dir);
  const pagesDir = path.join(dir, 'pages');
  fs.mkdirSync(pagesDir, { recursive: true });

  const manifest = buildManifest(b);
  fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n', 'utf8');
  for (const p of b.pages) {
    fs.writeFileSync(path.join(pagesDir, 'page-' + p + '.txt'), pageText(b['pages_' + p]), 'utf8');
  }

  const texts = { 18: pageText(b.pages_18), 30: pageText(b.pages_30), 37: pageText(b.pages_37), 46: pageText(b.pages_46) };

  // N6 纪律：P18/P30/P37 不得含「2024年」；P46 必须含
  check('N6-discipline', !texts['18'].includes('2024年') && !texts['30'].includes('2024年') && !texts['37'].includes('2024年') && texts['46'].includes('2024年'), 'P18/30/37 无「2024年」且 P46 含「2024年」');

  const t = b.metricsText;
  // N4 派生复算：不良率 = 不良余额/贷款总额×100（容差 0.05pp）；拨备 = 减值准备/不良×100（容差 1pp）；成本收入比 = 管理费/营收×100（容差 0.5pp）
  const loans = parseFloat(texts['30'].match(/(?:贷款和垫款本金总额|贷款总额)[^\d]{0,12}([\d,]+(?:\.\d+)?)/)[1].replace(/,/g, ''));
  const provBase = parseFloat(texts['30'].match(/贷款减值准备[^\d]{0,12}([\d,]+(?:\.\d+)?)/)[1].replace(/,/g, ''));
  const adminFee = parseFloat(texts['18'].match(/业务及管理费[^\d]{0,12}([\d,]+(?:\.\d+)?)/)[1].replace(/,/g, ''));
  check('N4-nplr', Math.abs(t.nplr - (t.npl / loans) * 100) < 0.05, t.nplr + ' vs ' + round2((t.npl / loans) * 100));
  check('N4-prov', Math.abs(t.prov - (provBase / t.npl) * 100) < 1, t.prov + ' vs ' + round2((provBase / t.npl) * 100));
  check('N4-cir', Math.abs(t.cir - (adminFee / t.rev) * 100) < 0.5, t.cir + ' vs ' + round2((adminFee / t.rev) * 100));

  // N6 同比复算（P46 上年值，容差 0.1）
  const priorRev = parseFloat(texts['46'].match(/营业收入([\d,]+(?:\.\d+)?)/)[1].replace(/,/g, ''));
  const priorProfit = parseFloat(texts['46'].match(/净利润([\d,]+(?:\.\d+)?)/)[1].replace(/,/g, ''));
  const revYoy = round2(((t.rev - priorRev) / priorRev) * 100);
  const profitYoy = round2(((t.profit - priorProfit) / priorProfit) * 100);
  check('N6-rev', Math.abs(Math.abs(revYoy) - Math.abs(t.revDelta)) < 0.1, t.revDelta + '% vs 复算 ' + revYoy + '%');
  check('N6-profit', Math.abs(Math.abs(profitYoy) - Math.abs(t.profitDelta)) < 0.1, t.profitDelta + '% vs 复算 ' + profitYoy + '%');

  // pie 值页内字面（页文本使用千分位格式）
  const fmtLocal = (v) => v.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const pieVals = [b.chartsText.pieInterest, b.chartsText.pieNonInterest];
  check('pie-literal', pieVals.every((v) => texts['18'].includes(fmtLocal(v))), pieVals.map(fmtLocal).join('/'));

  // 工件生成（正例 + error 件）
  const pos = buildArtifact(b, manifest, b.taskIdPos, null);
  const err = buildArtifact(b, manifest, b.taskIdErr, { corruptS2: true });
  fs.writeFileSync(path.join(dir, 'positive-artifact.json'), JSON.stringify(pos, null, 2) + '\n', 'utf8');
  fs.writeFileSync(path.join(dir, 'error-artifact.json'), JSON.stringify(err, null, 2) + '\n', 'utf8');
  console.log('  artifact: ' + dir + '/positive-artifact.json (citations=' + pos.citations.length + ', charts=' + pos.charts.length + ')');

  // 契约 schema 自检（对齐 Jackson FAIL_ON_UNKNOWN_PROPERTIES 严格白名单）
  const exactKeys = (arr, keys) => arr.every((o) => Object.keys(o).length === keys.length && keys.every((k) => k in o));
  check('schema-metrics', exactKeys(pos.metrics, METRIC_KEYS), 'MetricValue 9 字段白名单');
  check('schema-citations', exactKeys(pos.citations, CITATION_KEYS), 'Citation 6 字段白名单');

  // C2-L1/N3 节点侧预检：摘录与数值必须字面落在声明页（primary→P18/P30/P37，comparison→P46）
  const cmpDigest = manifest.sources[1].content_sha256;
  const pageOf = (digest, page) => (digest === cmpDigest ? texts['46'] : texts[page]);
  const numPresent = (pageText, value) => {
    const want = value.replace(/,/g, '');
    const re = /\d{1,3}(?:,\d{3})*(?:\.\d+)?|\d+(?:\.\d+)?/g;
    let m2;
    while ((m2 = re.exec(pageText)) !== null) if (m2[0].replace(/,/g, '') === want) return true;
    return false;
  };
  const excerptBad = pos.citations.filter((c) => !(pageOf(c.source_document_digest, c.page) || '').includes(c.evidence_excerpt));
  check('citation-excerpt-on-page', excerptBad.length === 0, excerptBad.map((c) => c.claim_id + '@P' + c.page).join(',') || '全部摘录命中声明页');
  const valueBad = pos.metrics.filter((m) => !numPresent(pageOf(m.source_document_digest, m.page) || '', m.value));
  check('metric-value-on-page', valueBad.length === 0, valueBad.map((m) => m.key + '@P' + m.page).join(',') || '全部数值命中声明页');

  // 骨架一致性：节序与标题必须与 report-structure.json 一致（配置是唯一事实源）
  check('skeleton-order', pos.sections.map((s) => s.section_id).join(',') === STRUCTURE.section_order.join(','), pos.sections.length + ' 节按 section_order');
  check('skeleton-titles', pos.sections.every((s) => s.title === titleOf(b.bank, s.section_id)), '标题全部来自 banks.' + b.bank + '.titles');
  check('charts-mounted', pos.charts.every((ch) => pos.sections.some((s) => s.section_id === ch.section_id)), pos.charts.map((ch) => ch.id + '@' + ch.section_id).join(', '));
}

// ——————— 7. demo 目录（CMB 正例+error，带 charts） ———————
fs.mkdirSync(DEMO_DIR, { recursive: true });
const cmbManifestPath = path.join(CMB_DIR, 'manifest.json');
const cmbManifest = JSON.parse(fs.readFileSync(cmbManifestPath, 'utf8'));
const cmbPositiveSrc = path.join(DEPLOY, 'positive-artifact.json');
const cmbErrorSrc = path.join(DEPLOY, 'error-artifact.json');
if (fs.existsSync(cmbPositiveSrc)) {
  const pos = JSON.parse(fs.readFileSync(cmbPositiveSrc, 'utf8'));
  const err = JSON.parse(fs.readFileSync(cmbErrorSrc, 'utf8'));
  // 骨架归一化（配置驱动）：标题/静态节体取 report-structure.json，节序按配置；charts 随数据文件自带，脚本不再硬编码
  normalizeSections(pos, cmbManifest, 'cmb');
  normalizeSections(err, cmbManifest, 'cmb');
  check('demo-skeleton-order', pos.sections.map((s) => s.section_id).join(',') === STRUCTURE.section_order.join(','), pos.sections.length + ' 节按 section_order');
  check('demo-static-filled', pos.sections.filter((s) => STRUCTURE.sections[s.section_id].role === 'static').every((s) => !s.body.includes('{') && !s.body.includes('undefined')), '静态节占位符全部替换');
  check('demo-charts-mounted', pos.charts.every((ch) => pos.sections.some((s) => s.section_id === ch.section_id)), pos.charts.map((ch) => ch.id + '@' + ch.section_id).join(', '));
  fs.writeFileSync(path.join(DEMO_DIR, 'positive-artifact.json'), JSON.stringify(pos, null, 2) + '\n', 'utf8');
  fs.writeFileSync(path.join(DEMO_DIR, 'error-artifact.json'), JSON.stringify(err, null, 2) + '\n', 'utf8');
  // 同步写入 snapshot/cmb/（preload 多银行读取）
  fs.writeFileSync(path.join(CMB_DIR, 'positive-artifact.json'), JSON.stringify(pos, null, 2) + '\n', 'utf8');
  fs.writeFileSync(path.join(CMB_DIR, 'error-artifact.json'), JSON.stringify(err, null, 2) + '\n', 'utf8');
  console.log('\n[demo] cmb positive/error normalized by report-structure.json (charts kept from data files)');
} else {
  console.log('\n[demo] deploy/positive-artifact.json absent — skip demo rewrite');
}

// ——————— 8. CMB digest 对照（复制完整性校验） ———————
if (fs.existsSync(path.join(CMB_DIR, 'pages'))) {
  console.log('\n== CMB digest 对照 ==');
  for (const [p, want] of Object.entries(CMB_DIGESTS.pages)) {
    const got = sha256(fs.readFileSync(path.join(CMB_DIR, 'pages', 'page-' + p + '.txt'), 'utf8'));
    check('cmb-page-' + p, got === want, got === want ? 'digest 稳定' : got);
  }
}

console.log(failures.length === 0 ? '\n全部自检通过 ✔' : '\n自检失败 ✘ ' + failures.length + ' 项');
process.exit(failures.length === 0 ? 0 : 1);
