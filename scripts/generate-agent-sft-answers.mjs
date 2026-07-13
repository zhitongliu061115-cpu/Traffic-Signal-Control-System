import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const DATA_DIR = path.join(ROOT, "docs", "agent-finetuning");
const REVIEW_PATH = path.join(DATA_DIR, "semantic-scenes-review.json");
const CANDIDATE_PATH = path.join(DATA_DIR, "semantic-scenes-candidates.jsonl");
const ANSWERED_PATH = path.join(DATA_DIR, "answered-sft-approved.jsonl");
const ANSWER_REVIEW_PATH = path.join(DATA_DIR, "answered-sft-review.jsonl");
const VALIDATION_PATH = path.join(DATA_DIR, "answered-sft-validation.json");
const PLANNER_STAGE_DIR = path.join(DATA_DIR, "planner-stage");

const INTENTS = new Set([
  "current_state",
  "detail_query",
  "decision_trace",
  "system_health",
  "knowledge",
  "diagnosis",
  "emergency",
  "configuration_audit",
  "direct_answer",
]);

const TOOL_SPECS = {
  get_current_simulation_state: { optional: ["sid"] },
  get_intersection_detail: { required: ["intersectionId"], optional: ["sid", "sceneCode"] },
  get_road_detail: { required: ["roadId"], optional: ["sid", "sceneCode"] },
  get_latest_control_decisions: { optional: ["sid", "intersectionId", "limit"] },
  get_decision_trace: { required: ["decisionId"] },
  get_system_health: { optional: ["limit"] },
  get_model_inference_log: { optional: ["sid", "intersectionId", "limit"] },
  search_knowledge_base: { required: ["query"], optional: ["topK", "scope"] },
  diagnose_congestion: { optional: ["targetType", "targetId", "sid", "sceneCode"] },
  detect_signal_anomaly: { optional: ["sid", "intersectionId", "limit"] },
  detect_spillback_risk: { optional: ["sid", "roadId", "intersectionId", "sceneCode"] },
  get_safety_constraint_log: { optional: ["sid", "intersectionId", "decisionId", "limit"] },
  get_fallback_log: { optional: ["sid", "intersectionId", "limit"] },
  get_region_metrics: { optional: ["sid", "regionId", "intersectionIds", "limit"] },
  compare_strategy_metrics: { optional: ["sids", "sceneCode", "limit"] },
  get_fallback_events: { optional: ["sid", "intersectionId", "limit"] },
  get_safety_events: { optional: ["sid", "intersectionId", "decisionId", "limit"] },
  get_alert_events: { optional: ["sid", "level", "status", "limit"] },
  get_emergency_events: { optional: ["sid", "status", "limit"] },
  get_emergency_vehicle_status: { optional: ["sid", "vehicleId", "limit"] },
  draft_emergency_dispatch: {
    required: ["startIntersection", "endIntersection"],
    optional: ["sid", "evId", "evType", "priority"],
  },
  audit_configuration_consistency: { optional: ["sid", "sceneCode"] },
};

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function readSystemPrompt() {
  const firstLine = fs.readFileSync(CANDIDATE_PATH, "utf8").split(/\r?\n/u, 1)[0];
  const firstSample = JSON.parse(firstLine);
  const systemMessage = firstSample.messages?.find((message) => message.role === "system");
  if (!systemMessage?.content) {
    throw new Error("候选文件首行缺少 system 消息");
  }
  return systemMessage.content;
}

function plannerUserContent(question, context, sid) {
  return `用户问题：\n${question}\n\n可用上下文 JSON：\n${JSON.stringify(context ?? {})}\n\n当前仿真 sid：\n${sid ?? ""}\n\n请只输出 JSON。`;
}

function validatePlan(sceneId, plan) {
  const errors = [];
  if (!plan || typeof plan !== "object" || Array.isArray(plan)) {
    return [`${sceneId}: goldPlan 必须是 JSON 对象`];
  }
  if (!INTENTS.has(plan.intent)) {
    errors.push(`${sceneId}: 非法 intent ${plan.intent}`);
  }
  if (typeof plan.needsTools !== "boolean") {
    errors.push(`${sceneId}: needsTools 必须是布尔值`);
  }
  if (typeof plan.rationale !== "string" || plan.rationale.trim() === "") {
    errors.push(`${sceneId}: rationale 不能为空`);
  }
  if (!Array.isArray(plan.toolCalls)) {
    return [...errors, `${sceneId}: toolCalls 必须是数组`];
  }
  if (plan.needsTools !== (plan.toolCalls.length > 0)) {
    errors.push(`${sceneId}: needsTools 与 toolCalls 不一致`);
  }
  if (plan.toolCalls.length > 4) {
    errors.push(`${sceneId}: toolCalls 超过 4 个`);
  }
  for (const [callIndex, call] of plan.toolCalls.entries()) {
    const prefix = `${sceneId}: toolCalls[${callIndex}]`;
    const spec = TOOL_SPECS[call.toolName];
    if (!spec) {
      errors.push(`${prefix} 使用非法工具 ${call.toolName}`);
      continue;
    }
    if (!call.arguments || typeof call.arguments !== "object" || Array.isArray(call.arguments)) {
      errors.push(`${prefix} arguments 必须是对象`);
      continue;
    }
    if (typeof call.reason !== "string" || call.reason.trim() === "") {
      errors.push(`${prefix} reason 不能为空`);
    }
    const allowedArguments = new Set([...(spec.required ?? []), ...(spec.optional ?? [])]);
    for (const [name, value] of Object.entries(call.arguments)) {
      if (!allowedArguments.has(name)) {
        errors.push(`${prefix} 包含非法参数 ${name}`);
      }
      if (value === null || value === "") {
        errors.push(`${prefix} 参数 ${name} 不得为空`);
      }
      if (["limit", "topK", "priority"].includes(name) && (!Number.isInteger(value) || value <= 0)) {
        errors.push(`${prefix} 参数 ${name} 必须是正整数`);
      }
    }
    for (const requiredName of spec.required ?? []) {
      if (!(requiredName in call.arguments) || String(call.arguments[requiredName]).trim() === "") {
        errors.push(`${prefix} 缺少必填参数 ${requiredName}`);
      }
    }
    for (const requiredGroup of spec.requiredAny ?? []) {
      const hasRequiredValue = requiredGroup.some((name) => {
        const value = call.arguments[name];
        return value !== undefined && value !== null && String(value).trim() !== "";
      });
      if (!hasRequiredValue) {
        errors.push(`${prefix} 至少需要参数之一 ${requiredGroup.join("/")}`);
      }
    }
  }
  return errors;
}

function increment(counter, key) {
  counter[key] = (counter[key] ?? 0) + 1;
}

const VALIDATION_SCENES = new Set([
  "RT-002", "IN-002", "TR-003", "HL-002", "KB-002",
  "DG-002", "EV-002", "ED-002", "CA-002", "DA-002",
]);

const TEST_SCENES = new Set([
  "RT-003", "RD-002", "TR-004", "HL-003", "KB-003",
  "AN-003", "EE-003", "ED-003", "CA-003", "DA-003",
]);

function splitForScene(scene) {
  if (VALIDATION_SCENES.has(scene.sceneId)) return "validation";
  if (TEST_SCENES.has(scene.sceneId)) return "test";
  return "train";
}

const source = readJson(REVIEW_PATH);
const systemPrompt = readSystemPrompt();
const scenes = source.scenes ?? [];
const errors = [];
const sampleIds = new Set();
const questions = new Set();
const answeredSamples = [];
const answerReviewRows = [];
const intentCounts = {};
const toolCounts = {};
const datasets = { train: [], validation: [], test: [] };
const splitSceneIds = { train: new Set(), validation: new Set(), test: new Set() };
const splitIntentCounts = { train: {}, validation: {}, test: {} };
const splitToolCounts = { train: {}, validation: {}, test: {} };

for (const scene of scenes) {
  const split = splitForScene(scene);
  splitSceneIds[split].add(scene.sceneId);
  errors.push(...validatePlan(scene.sceneId, scene.goldPlan));
  increment(intentCounts, scene.goldPlan?.intent ?? "missing");
  for (const call of scene.goldPlan?.toolCalls ?? []) {
    increment(toolCounts, call.toolName);
  }
  if (!Array.isArray(scene.paraphrases) || scene.paraphrases.length === 0) {
    errors.push(`${scene.sceneId}: paraphrases 不能为空`);
    continue;
  }
  for (const [questionIndex, question] of scene.paraphrases.entries()) {
    const sampleId = `${scene.sceneId}-${String(questionIndex + 1).padStart(2, "0")}`;
    if (sampleIds.has(sampleId)) {
      errors.push(`${sampleId}: sampleId 重复`);
    }
    sampleIds.add(sampleId);
    if (questions.has(question)) {
      errors.push(`${sampleId}: 问题文本重复`);
    }
    questions.add(question);

    const answer = JSON.stringify(scene.goldPlan);
    const trainingSample = {
      messages: [
        { role: "system", content: systemPrompt },
        {
          role: "user",
          content: plannerUserContent(question, scene.inputContext, scene.currentSid),
        },
        { role: "assistant", content: answer },
      ],
    };

    try {
      const roundTrip = JSON.parse(JSON.stringify(trainingSample));
      JSON.parse(roundTrip.messages[2].content);
      if (roundTrip.messages.map((message) => message.role).join(",") !== "system,user,assistant") {
        errors.push(`${sampleId}: messages 角色顺序错误`);
      }
    } catch (error) {
      errors.push(`${sampleId}: 训练样本无法解析，${error.message}`);
    }

    answeredSamples.push(trainingSample);
    datasets[split].push(trainingSample);
    increment(splitIntentCounts[split], scene.goldPlan.intent);
    for (const call of scene.goldPlan.toolCalls) increment(splitToolCounts[split], call.toolName);
    answerReviewRows.push({
      sampleId,
      sceneId: scene.sceneId,
      assignedSplit: split,
      category: scene.category,
      question,
      answer: scene.goldPlan,
    });
  }
}

if (scenes.length !== 100) {
  errors.push(`场景数为 ${scenes.length}，预期为 100`);
}
if (answeredSamples.length !== 1200) {
  errors.push(`回答样本数为 ${answeredSamples.length}，预期为 1200`);
}

const expectedSplitCounts = { train: 960, validation: 120, test: 120 };
for (const [split, expected] of Object.entries(expectedSplitCounts)) {
  if (datasets[split].length !== expected) {
    errors.push(`${split} 样本数为 ${datasets[split].length}，预期为 ${expected}`);
  }
}
for (const [left, right] of [["train", "validation"], ["train", "test"], ["validation", "test"]]) {
  const overlap = [...splitSceneIds[left]].filter((sceneId) => splitSceneIds[right].has(sceneId));
  if (overlap.length > 0) errors.push(`${left}/${right} 存在场景泄漏：${overlap.join(",")}`);
}

const plannerManifest = {
  generatedAt: new Date().toISOString(),
  task: "tool-planning-sft",
  source: path.relative(ROOT, REVIEW_PATH).replaceAll("\\", "/"),
  reviewStatus: "GENERATED_NOT_HUMAN_APPROVED",
  splitStrategy: "按完整 sceneId 分层隔离；validation/test 均覆盖 9 个核心 intent，每个场景的 12 个改写只进入一个集合。",
  combinedDataset: "train-validation.jsonl 由 train 与 validation 合并，共 1080 条；test 保持独立。",
  counts: {
    scenes: scenes.length,
    train: datasets.train.length,
    validation: datasets.validation.length,
    test: datasets.test.length,
    trainValidation: datasets.train.length + datasets.validation.length,
    total: answeredSamples.length,
  },
  splitSceneIds: Object.fromEntries(Object.entries(splitSceneIds).map(([split, values]) => [split, [...values]])),
  splitIntentCounts,
  splitToolCounts,
};

const report = {
  generatedAt: plannerManifest.generatedAt,
  source: path.relative(ROOT, REVIEW_PATH).replaceAll("\\", "/"),
  status: errors.length === 0 ? "PASS" : "FAIL",
  sceneCount: scenes.length,
  answeredSampleCount: answeredSamples.length,
  uniqueSampleIdCount: sampleIds.size,
  uniqueQuestionCount: questions.size,
  intentCounts,
  toolCounts,
  splitCounts: plannerManifest.counts,
  splitSceneIds: plannerManifest.splitSceneIds,
  reviewStatus: plannerManifest.reviewStatus,
  errors,
};

fs.mkdirSync(PLANNER_STAGE_DIR, { recursive: true });
fs.writeFileSync(
  ANSWERED_PATH,
  `${answeredSamples.map((sample) => JSON.stringify(sample)).join("\n")}\n`,
  "utf8",
);
fs.writeFileSync(
  ANSWER_REVIEW_PATH,
  `${answerReviewRows.map((row) => JSON.stringify(row)).join("\n")}\n`,
  "utf8",
);
fs.writeFileSync(VALIDATION_PATH, `${JSON.stringify(report, null, 2)}\n`, "utf8");
for (const split of ["train", "validation", "test"]) {
  fs.writeFileSync(
    path.join(PLANNER_STAGE_DIR, `${split}.jsonl`),
    `${datasets[split].map((sample) => JSON.stringify(sample)).join("\n")}\n`,
    "utf8",
  );
}
const trainValidation = [...datasets.train, ...datasets.validation];
fs.writeFileSync(
  path.join(PLANNER_STAGE_DIR, "train-validation.jsonl"),
  `${trainValidation.map((sample) => JSON.stringify(sample)).join("\n")}\n`,
  "utf8",
);
fs.writeFileSync(path.join(PLANNER_STAGE_DIR, "manifest.json"), `${JSON.stringify(plannerManifest, null, 2)}\n`, "utf8");

console.log(JSON.stringify(report, null, 2));
if (errors.length > 0) {
  process.exitCode = 1;
}
