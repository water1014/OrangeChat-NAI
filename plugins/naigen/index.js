/**
 * NAI 生图插件（柏宝绘风格）
 *
 * 插件只负责「提示词拼装 + 参数决策」，真正的请求、解包、落盘由宿主的
 * __imageBridge('generate', ...) 完成，所以不需要在 JS 里处理 zip / base64。
 */

// 对齐柏宝绘：4-5 系列官方负面（中性，不含性别/身材/题材偏好，只排缺陷与画质问题）
var DEFAULT_NEGATIVE_V45 =
  'lowres, artistic error, film grain, scan artifacts, worst quality, bad quality, jpeg artifacts, ' +
  'very displeasing, chromatic aberration, dithering, halftone, screentone, multiple views, ' +
  'logo, too many watermarks, negative space, blank page';
// 5 系列官方负面（更精简，含 plastic/waxy skin 一类材质排错）
var DEFAULT_NEGATIVE_V5 =
  'lowres, artistic error, film grain, scan artifacts, worst quality, bad quality, jpeg artifacts, ' +
  'very displeasing, chromatic aberration, dithering, halftone, screentone, multiple views, ' +
  'logo, too many watermarks, negative space, blank page, text, watermark, signature, username, ' +
  'artist name, bad anatomy, bad hands, bad feet, extra digits, fewer digits, extra fingers, ' +
  'fused fingers, extra limbs, missing limbs, long neck, blurry, poorly drawn, unfinished, ' +
  'plastic skin, waxy skin, oversaturated, washed out';
// 旧模型兜底负面
var DEFAULT_NEGATIVE =
  'lowres, worst quality, bad quality, jpeg artifacts, watermark, signature, text, ' +
  'bad anatomy, bad hands, extra fingers, fused fingers';

// 素图模式的最小负面
var MINIMAL_NEGATIVE = 'lowres, worst quality, jpeg artifacts, watermark';

/** 按模型选官方负面词；用户没自定义时生效 */
function defaultNegativeForModel(model) {
  var m = String(model || '');
  if (m.indexOf('nai-diffusion-5') >= 0) return DEFAULT_NEGATIVE_V5;
  if (m.indexOf('4-5') >= 0 || m.indexOf('4.5') >= 0) return DEFAULT_NEGATIVE_V45;
  return DEFAULT_NEGATIVE;
}

/**
 * 只压「真正带 artist: 前缀」的画师权重（NAI 语法 :artist:name:）。
 * 不动用户自己写的加权正向 tag（如 2.5::realistic face::、3::gray tones::）——那些是
 * 精心调的强调，压平了反而出不了好图。柏宝绘也不做这种全局压权。
 */
function limitArtistWeight(text, max) {
  if (!text || !max || Number(max) <= 0) return text;
  var limit = Number(max).toFixed(1).replace(/\.0$/, '');
  return text.replace(/(\d+(?:\.\d+)?)(\s*:\s*artist\s*:)/gi, function (m, w, rest) {
    return parseFloat(w) > Number(max) ? limit + rest : m;
  });
}

function cfg(name, fallback) {
  if (typeof config === 'undefined' || !config) return fallback;
  var v = config[name];
  if (v === undefined || v === null || v === '') return fallback;
  return v;
}

/** 解析角色档案 JSON，失败返回空对象 */
function characters() {
  try {
    var raw = cfg('charactersJson', '');
    if (!raw) return {};
    var parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch (e) {
    return {};
  }
}

/** 把 @名字 换成档案里的外貌，长名字优先，避免 @小 抢先匹配 @小明 */
function expandCharacters(text) {
  var lib = characters();
  var names = Object.keys(lib).sort(function (a, b) { return b.length - a.length; });
  var out = text;
  for (var i = 0; i < names.length; i++) {
    var name = names[i];
    var look = String(lib[name] || '').trim();
    if (!look) continue;
    out = out.split('@' + name).join(look);
  }
  return out;
}

/** 把模型 ID 映射到 .naiv4vibe 里 encodings 的分组键 */
function vibeModelKey(model) {
  var m = String(model || '');
  if (m.indexOf('nai-diffusion-5-curated') >= 0) return 'v5curated';
  if (m.indexOf('nai-diffusion-5-full') >= 0) return 'v5full';
  if (m.indexOf('nai-diffusion-5') >= 0) return 'v5full';
  if (m.indexOf('4-5-curated') >= 0) return 'v4-5curated';
  if (m.indexOf('4-5-full') >= 0) return 'v4-5full';
  if (m.indexOf('4-curated') >= 0) return 'v4curated';
  if (m.indexOf('4-full') >= 0) return 'v4full';
  if (m.indexOf('diffusion-3') >= 0) return 'v3';
  return '';
}

/**
 * 从一份 .naiv4vibe 里挖出当前模型可用的编码串。
 * 官方文件长这样：encodings["v4-5full"]["unknown"]["encoding"]，
 * 也有直接 encodings["v4-5full"]["encoding"] 的变体，两种都收。
 */
function pickEncoding(vibe) {
  var encodings = vibe.encodings;
  if (!encodings || typeof encodings !== 'object') return '';
  var model = String(cfg('model', ''));
  var key = vibeModelKey(model);
  var node = key && encodings[key];
  if (!node) {
    // 当前模型没有专门编码时，退到任意一组（同代模型编码通常可通用）
    var keys = Object.keys(encodings);
    for (var i = 0; i < keys.length; i++) {
      if (encodings[keys[i]] && typeof encodings[keys[i]] === 'object') {
        node = encodings[keys[i]];
        break;
      }
    }
  }
  if (!node || typeof node !== 'object') return '';
  if (typeof node.encoding === 'string' && node.encoding.length > 64) return node.encoding;
  var sub = Object.keys(node);
  for (var j = 0; j < sub.length; j++) {
    var child = node[sub[j]];
    if (child && typeof child === 'object' && typeof child.encoding === 'string' && child.encoding.length > 64) {
      return child.encoding;
    }
  }
  return '';
}

/**
 * 解析 vibe 预设。两种写法都收：
 * 1) 数组：[{"name","encoded","strength"}]（插件自己的格式）
 * 2) 直接粘贴 .naiv4vibe 原文（单个对象，或数组里放多个原文）
 */
function parseVibes() {
  try {
    var raw = cfg('vibesJson', '');
    if (!raw) return [];
    var parsed = JSON.parse(raw);
    var list = Array.isArray(parsed) ? parsed : [parsed];
    var out = [];
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (!v || typeof v !== 'object') continue;
      var encoded = typeof v.encoded === 'string' ? v.encoded : '';
      var strength = v.strength;
      var model = v.model;
      var name = v.name;
      // .naiv4vibe 原文：编码藏在 encodings 里
      if (!encoded || encoded.length <= 64) {
        encoded = pickEncoding(v);
        if (v.importInfo && typeof v.importInfo === 'object') {
          if (strength === undefined) strength = v.importInfo.strength;
          if (!model) model = v.importInfo.model;
        }
        if (!name) name = v.name || 'vibe' + (i + 1);
      }
      if (!encoded || encoded.length <= 64) continue;
      if (v.enabled === false) continue;
      var s = strength === undefined || strength === null ? 0.6 : Number(strength);
      out.push({
        name: String(name || 'vibe' + (i + 1)),
        encoded: encoded,
        strength: isNaN(s) ? 0.6 : Math.max(0, Math.min(1, s)),
        model: String(model || ''),
        enabled: true
      });
    }
    return out.slice(0, 4);
  } catch (e) {
    return [];
  }
}

function hasArtist(text) {
  if (!text) return false;
  var lower = text.toLowerCase();
  if (lower.indexOf('artist:') >= 0) return true;
  if (lower.indexOf('::') >= 0) return true;
  var artist = String(cfg('artist', '')).toLowerCase();
  return artist.length > 0 && lower.indexOf(artist) >= 0;
}

function hasQuality(text) {
  var lower = (text || '').toLowerCase();
  var words = ['masterpiece', 'best quality', 'very aesthetic', 'absurdres', 'highest quality'];
  for (var i = 0; i < words.length; i++) {
    if (lower.indexOf(words[i]) >= 0) return true;
  }
  return false;
}

function mergeNegative(base, extra) {
  var seen = {};
  var out = [];
  var parts = String(base || '').split(',').concat(String(extra || '').split(','));
  for (var i = 0; i < parts.length; i++) {
    var word = parts[i].trim();
    if (!word) continue;
    var key = word.toLowerCase();
    if (seen[key]) continue;
    seen[key] = true;
    out.push(word);
  }
  return out.join(', ');
}

exports.nai_generate = function (params) {
  params = params || {};
  var raw = String(params.prompt || '').trim();
  if (!raw) {
    return { success: false, error: 'prompt 不能为空' };
  }

  var protocol = String(cfg('protocol', 'AUTO')).toUpperCase();
  // AUTO：主路径按 NAI 原生打（参数最全），宿主在 404 时自动回退 OpenAI 兼容。
  // 这里把 AUTO 当原生处理，质量词交给 qualityToggle、负向走字段，
  // 避免回退前的第一枪就把负面词写进正向提示词。
  var isNai = protocol === 'NOVELAI' || protocol === 'AUTO';

  var plain = params.plain === true || String(params.plain) === 'true';
  var prompt = expandCharacters(raw);

  // 素图模式：不带画师串、不加质量词，先看基础画面
  if (!plain) {
    var artist = String(cfg('artist', '')).trim();
    if (artist && !hasArtist(prompt)) {
      prompt = prompt ? artist + ', ' + prompt : artist;
    }
  }

  // 画师权重限幅：默认 1.2
  prompt = limitArtistWeight(prompt, cfg('maxArtistWeight', 1.2));

  // NAI 原生由 qualityToggle 负责质量词；OpenAI 兼容渠道要自己补
  var qualityOn = cfg('qualityTags', true) === true || String(cfg('qualityTags', 'true')) === 'true';
  if (!plain && !isNai && qualityOn && !hasQuality(prompt)) {
    prompt = prompt + ', best quality, very aesthetic, absurdres';
  }

  var negative = plain
    ? MINIMAL_NEGATIVE
    : mergeNegative(cfg('negative', defaultNegativeForModel(cfg('model', 'nai-diffusion-4-5-full'))), params.negative || '');

  // OpenAI 兼容接口没有 negative 字段，按 NAI 中转的惯例拼在正文尾部
  var negativeField = negative;
  if (!isNai && negative) {
    prompt = prompt + '\n\nNegative prompt: ' + negative;
    negativeField = '';
  }

  var vibes = plain ? [] : parseVibes();

  var payload = {
    baseUrl: cfg('baseUrl', ''),
    apiKey: cfg('apiKey', ''),
    protocol: protocol,
    model: cfg('model', 'nai-diffusion-4-5-full'),
    vibes: vibes,
    prompt: prompt,
    negative: negativeField,
    width: Number(params.width || cfg('width', 832)),
    height: Number(params.height || cfg('height', 1216)),
    count: Number(params.count || 1),
    seed: Number(params.seed === undefined ? -1 : params.seed),
    steps: Number(cfg('steps', 28)),
    scale: Number(cfg('scale', 4)),
    sampler: String(cfg('sampler', 'Euler Ancestral')),
    noiseSchedule: String(cfg('noiseSchedule', 'Karras')),
    ucPreset: Number(cfg('ucPreset', 3)),
    sm: cfg('smea', false) === true || String(cfg('smea')) === 'true',
    smDyn: cfg('smeaDyn', false) === true || String(cfg('smeaDyn')) === 'true',
    varietyBoost: cfg('varietyBoost', false) === true || String(cfg('varietyBoost')) === 'true',
    dynamicThresholding: cfg('dynamicThresholding', false) === true || String(cfg('dynamicThresholding')) === 'true',
    // 严格模式下 OpenAI 兼容只发 model/prompt/n/size，避免未知字段被判 400
    strictOpenAI: !(cfg('strictOpenAI', true) === false || String(cfg('strictOpenAI')) === 'false')
  };

  if (!payload.baseUrl) {
    return { success: false, error: '插件还没配置站点地址：插件详情 → 配置 → 站点地址' };
  }

  var res;
  try {
    res = JSON.parse(__imageBridge('generate', JSON.stringify(payload)));
  } catch (e) {
    return { success: false, error: '调用宿主生图失败: ' + (e.message || String(e)) };
  }

  if (!res || !res.success) {
    return { success: false, error: (res && res.error) || '生成失败', prompt: prompt, negative: negative };
  }

  return {
    success: true,
    prompt: prompt,
    negative: negative,
    protocol: protocol,
    plain: plain,
    cfg_scale: payload.scale,
    sampler: payload.sampler,
    vibe_count: vibes.length,
    size: payload.width + 'x' + payload.height,
    seeds: res.seeds || [],
    images: res.images || [],
    hint: '图片已写入图库「橘瓣生成」，可直接在对话里查看'
  };
};
