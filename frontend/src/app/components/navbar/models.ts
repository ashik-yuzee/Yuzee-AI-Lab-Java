import { ModelCapabilityInfo } from '../../models/types';

/**
 * From yuzee-ai-token-lab/src/data/models.ts (GEMINI_MODELS — the Navbar's fallback when the
 * server's capabilities.modelsList is absent, as in the original) and src/routing/models.ts
 * (RUNTIME_ROUTER_MODELS for the router-model select).
 */
export const GEMINI_MODELS: ModelCapabilityInfo[] = [
  { id: 'gemini-3.7-flash', name: 'Gemini 3.7 Flash', shortDescription: 'Newest, most capable Flash model for complex reasoning and multi-step tasks.', longDescription: 'Newest and most capable current Flash model. Strong for complex reasoning, coding and multi-step execution.', family: 'flash', categoryGroup: 'Current', status: 'current', selectable: true, isDefault: true, badge: 'Default', inputPricePerMToken: 0.10, outputPricePerMToken: 0.40, cachedReadPricePerMToken: 0.025 },
  { id: 'gemini-3.8-flash', name: 'Gemini 3.8 Flash', shortDescription: 'Latest Flash model — faster and more efficient than 3.7.', longDescription: 'Latest Flash generation with improved speed and efficiency over 3.7 Flash.', family: 'flash', categoryGroup: 'Current', status: 'stable', selectable: true, badge: 'New', inputPricePerMToken: 0.10, outputPricePerMToken: 0.40, cachedReadPricePerMToken: 0.025 },
  { id: 'gemini-3.6-flash', name: 'Gemini 3.6 Flash', shortDescription: 'Fast, high-quality general model with balanced intelligence and token efficiency.', longDescription: 'Fast, high-quality general Flash model with a strong balance between capability and token efficiency.', family: 'flash', categoryGroup: 'Current', status: 'stable', selectable: true, isRecommended: true, badge: 'Recommended', inputPricePerMToken: 0.10, outputPricePerMToken: 0.40, cachedReadPricePerMToken: 0.025 },
  { id: 'gemini-3.5-flash', name: 'Gemini 3.5 Flash', shortDescription: 'Balanced Flash model — strong quality with full thinking support.', longDescription: 'High-capability Flash model with a strong balance between quality and cost.', family: 'flash', categoryGroup: 'Current', status: 'stable', selectable: true, badge: 'Stable', inputPricePerMToken: 0.10, outputPricePerMToken: 0.40, cachedReadPricePerMToken: 0.025 },
  { id: 'gemini-3.5-flash-lite', name: 'Gemini 3.5 Flash-Lite', shortDescription: 'Fast and efficient Flash-Lite model for lower-latency and high-throughput workloads.', longDescription: 'Fast and efficient Flash-Lite model intended for lower-latency and high-throughput workloads.', family: 'flash-lite', categoryGroup: 'Flash-Lite', status: 'stable', selectable: true, badge: 'Fast', inputPricePerMToken: 0.075, outputPricePerMToken: 0.30, cachedReadPricePerMToken: 0.019 },
  { id: 'gemini-3.1-flash-lite', name: 'Gemini 3.1 Flash-Lite', shortDescription: 'Earlier Flash-Lite generation useful as an efficiency and migration baseline.', longDescription: 'Earlier Flash-Lite generation useful as an efficiency and migration baseline.', family: 'flash-lite', categoryGroup: 'Flash-Lite', status: 'stable', selectable: true, inputPricePerMToken: 0.075, outputPricePerMToken: 0.30, cachedReadPricePerMToken: 0.019 },
  { id: 'gemini-2.5-flash', name: 'Gemini 2.5 Flash', shortDescription: 'Legacy hybrid-reasoning model for comparing older thinking-budget behavior.', longDescription: 'Legacy hybrid-reasoning Flash model. Useful for comparing token usage against Gemini 3.x.', family: 'legacy', categoryGroup: 'Legacy comparison', status: 'legacy', selectable: true, badge: 'Legacy', inputPricePerMToken: 0.10, outputPricePerMToken: 0.40, cachedReadPricePerMToken: 0.025 },
  { id: 'gemini-2.5-flash-lite', name: 'Gemini 2.5 Flash-Lite', shortDescription: 'Legacy lightweight Gemini 2.5 model useful as an older efficiency baseline.', longDescription: 'Legacy lightweight Gemini 2.5 model useful as an older efficiency baseline.', family: 'legacy', categoryGroup: 'Legacy comparison', status: 'legacy', selectable: true, badge: 'Legacy', inputPricePerMToken: 0.038, outputPricePerMToken: 0.15, cachedReadPricePerMToken: 0.010 },
  { id: 'gemini-2.0-flash', name: 'Gemini 2.0 Flash', shortDescription: 'Retired. No longer callable. Replacement: Gemini 3.6 Flash.', longDescription: 'Retired model generation. Displayed for historical comparison only; not callable.', family: 'legacy', categoryGroup: 'Retired', status: 'retired', selectable: false, badge: 'Retired' },
  { id: 'gemini-2.0-flash-lite', name: 'Gemini 2.0 Flash-Lite', shortDescription: 'Retired. No longer callable. Replacement: Gemini 3.5 Flash-Lite.', longDescription: 'Retired model generation. Displayed for historical comparison only; not callable.', family: 'legacy', categoryGroup: 'Retired', status: 'retired', selectable: false, badge: 'Retired' },
];

export const ROUTER_MODEL_KEY = 'oala-router-model';
export const RUNTIME_ROUTER_MODELS = [
  { id: 'Xenova/bge-small-en-v1.5', label: 'BGE-small', description: 'Calibrated skill matching · 512-token input window' },
  { id: 'cloudflare/llama-3.1-8b-fast', label: 'Llama 3.1-8b Fast', description: 'Server-side LLM routing via Cloudflare Workers AI · requires CLOUDFLARE_API_TOKEN' },
];
export const DEFAULT_ROUTER_MODEL = RUNTIME_ROUTER_MODELS[0].id;
