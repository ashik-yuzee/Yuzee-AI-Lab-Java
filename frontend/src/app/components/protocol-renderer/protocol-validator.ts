/**
 * Port of the original app's src/protocol/validator.ts (+ orchestration/serviceActions.ts
 * TRUSTED_SERVICE_ACTIONS). Same rules, same messages.
 *
 * Layer 1 used Ajv against the v1.3/v1.4 JSON schemas; Ajv is not a dependency of this app, so
 * `checkSchema` below implements the JSON-Schema keywords those two schema files use (type,
 * properties, required, additionalProperties, enum, const, items, maxItems, maxLength, minimum,
 * maximum, allOf, if/then) with Ajv's messages and error order.
 */
import SCHEMA_V13 from './Yuzee_Response_Schema_v1.3.json';
import SCHEMA_V14 from './Yuzee_Response_Schema_v1.4.json';

export interface ExtendedProtocolValidationResult {
  jsonParsed: boolean;
  schemaValid: boolean;
  semanticValid: boolean;
  protocolAccepted: boolean;
  schemaErrors: string[];
  semanticErrors: string[];
  errors: string[];
  warnings: string[];
}

export interface TrustedServiceAction {
  actionId: string;
  title: string;
  description: string;
  category: string;
  requiresConfirmation: boolean;
  enabled: boolean;
  isConnectedInLab: boolean;
}

/** Authoritative executable-action bindings. Product catalogue entries alone grant no capability. */
export const TRUSTED_SERVICE_ACTIONS: Record<string, TrustedServiceAction> = {
  'rmo_explore_courses': {
    actionId: 'rmo_explore_courses',
    title: 'Explore Certified Courses',
    description: 'Browse accredited Australian university and VET pathway courses matching your career goal.',
    category: 'RMO',
    requiresConfirmation: false,
    enabled: true,
    isConnectedInLab: false,
  },
  'rmo_apply_job': {
    actionId: 'rmo_apply_job',
    title: 'Apply for Verified Job Role',
    description: 'Submit an application directly to verified industry employer partners.',
    category: 'RMO',
    requiresConfirmation: true,
    enabled: true,
    isConnectedInLab: false,
  },
  'rmo_book_counsellor': {
    actionId: 'rmo_book_counsellor',
    title: 'Book 1-on-1 Senior Counsellor Session',
    description: 'Connect with a certified human education and career advisor.',
    category: 'ADVISORY',
    requiresConfirmation: true,
    enabled: true,
    isConnectedInLab: false,
  },
  'direct_admission_start': {
    actionId: 'direct_admission_start',
    title: 'Initiate Direct Admission',
    description: 'Start structured intake and document verification for priority admission.',
    category: 'DIRECT_APPLICATION',
    requiresConfirmation: true,
    enabled: true,
    isConnectedInLab: false,
  },
};

// ---------------------------------------------------------------------------
// JSON-Schema checker for the keywords present in the two schema files, producing Ajv 8's
// messages in Ajv's evaluation order (allErrors: true, strict: false): untyped keywords (const,
// enum, allOf, if/then) first, then the number, string, array and object keyword groups; a type
// error is reported up front, or at its group's position when the schema has keywords for
// that single type.
// ---------------------------------------------------------------------------

function typeMatches(value: unknown, type: string): boolean {
  switch (type) {
    case 'object': return !!value && typeof value === 'object' && !Array.isArray(value);
    case 'array': return Array.isArray(value);
    case 'string': return typeof value === 'string';
    case 'boolean': return typeof value === 'boolean';
    case 'null': return value === null;
    case 'integer': return typeof value === 'number' && !(value % 1) && !isNaN(value);
    case 'number': return typeof value === 'number';
    default: return true;
  }
}

const KEYWORD_GROUPS: [string, string[]][] = [
  ['number', ['maximum', 'minimum']],
  ['string', ['maxLength']],
  ['array', ['maxItems', 'items']],
  ['object', ['required', 'additionalProperties', 'properties']],
];

function checkSchema(schema: any, value: any, path: string, errors: string[]): void {
  if (!schema || typeof schema !== 'object') return;
  const at = path || 'root';
  const err = (message: string) => errors.push(`[Schema] ${at}: ${message}`);
  const types: string[] = schema.type === undefined ? [] : Array.isArray(schema.type) ? schema.type : [schema.type];
  const used = (group: string) => KEYWORD_GROUPS.find(g => g[0] === group)?.[1].some(k => schema[k] !== undefined) ?? false;
  const typeAtGroup = types.length === 1 && used(types[0]);
  const wrongType = types.length > 0 && !types.some(t => typeMatches(value, t));
  if (wrongType && !typeAtGroup) err(`must be ${types.join(',')}`);

  if (schema.const !== undefined && JSON.stringify(value) !== JSON.stringify(schema.const)) err('must be equal to constant');
  if (Array.isArray(schema.enum) && !schema.enum.some((e: unknown) => JSON.stringify(e) === JSON.stringify(value))) err('must be equal to one of the allowed values');
  if (Array.isArray(schema.allOf)) for (const sub of schema.allOf) checkSchema(sub, value, path, errors);
  if (schema.if !== undefined && schema.then !== undefined) {
    const probe: string[] = [];
    checkSchema(schema.if, value, path, probe);
    if (!probe.length) {
      const before = errors.length;
      checkSchema(schema.then, value, path, errors);
      if (errors.length > before) err('must match "then" schema');
    }
  }

  for (const [group] of KEYWORD_GROUPS) {
    if (!used(group)) continue;
    if (!typeMatches(value, group)) { if (typeAtGroup && wrongType && types[0] === group) err(`must be ${group}`); continue; }
    if (group === 'number') {
      if (typeof schema.maximum === 'number' && value > schema.maximum) err(`must be <= ${schema.maximum}`);
      if (typeof schema.minimum === 'number' && value < schema.minimum) err(`must be >= ${schema.minimum}`);
    } else if (group === 'string') {
      if (typeof schema.maxLength === 'number' && [...value].length > schema.maxLength) err(`must NOT have more than ${schema.maxLength} characters`);
    } else if (group === 'array') {
      if (typeof schema.maxItems === 'number' && value.length > schema.maxItems) err(`must NOT have more than ${schema.maxItems} items`);
      if (schema.items && typeof schema.items === 'object') value.forEach((v: unknown, i: number) => checkSchema(schema.items, v, `${path}/${i}`, errors));
    } else {
      const props = schema.properties && typeof schema.properties === 'object' ? schema.properties : {};
      for (const key of (Array.isArray(schema.required) ? schema.required : [])) if (value[key] === undefined) err(`must have required property '${key}'`);
      if (schema.additionalProperties !== undefined && schema.additionalProperties !== true) {
        for (const key in value) {
          if (Object.prototype.hasOwnProperty.call(props, key)) continue;
          if (schema.additionalProperties === false) err('must NOT have additional properties');
          else checkSchema(schema.additionalProperties, value[key], `${path}/${key}`, errors);
        }
      }
      for (const key of Object.keys(props)) if (value[key] !== undefined) checkSchema(props[key], value[key], `${path}/${key}`, errors);
    }
  }
}

function schemaErrorsFor(schema: any, json: any): string[] {
  const errors: string[] = [];
  checkSchema(schema, json, '', errors);
  return errors;
}

const NOT_OBJECT: ExtendedProtocolValidationResult = {
  jsonParsed: false, schemaValid: false, semanticValid: false, protocolAccepted: false,
  schemaErrors: ['Response is not a valid JSON object'], semanticErrors: [],
  errors: ['Response is not a valid JSON object'], warnings: [],
};

export function validateProtocolV13(json: any): ExtendedProtocolValidationResult {
  const semanticErrors: string[] = [];
  const warnings: string[] = [];
  if (!json || typeof json !== 'object' || Array.isArray(json)) return { ...NOT_OBJECT };

  const ajvErrors = schemaErrorsFor(SCHEMA_V13, json);
  const schemaErrors: string[] = [...ajvErrors];
  if (json.schema_version !== '1.3') schemaErrors.push(`Invalid schema_version: expected "1.3", received "${json.schema_version}"`);
  if (!Array.isArray(json.content_blocks)) schemaErrors.push('content_blocks must be an array');
  if (!json.interaction || typeof json.interaction !== 'object') schemaErrors.push('interaction object is required in envelope');
  const serviceObj = json.service_trigger;
  if (!serviceObj || typeof serviceObj !== 'object') schemaErrors.push('service_trigger object is required in envelope');
  if (!json.state || typeof json.state !== 'object') schemaErrors.push('state object is required in envelope');
  if (!json.followups || typeof json.followups !== 'object') schemaErrors.push('followups object is required in envelope');

  const schemaValid = ajvErrors.length === 0 && schemaErrors.length === 0;
  if (!schemaValid) return { jsonParsed: true, schemaValid: false, semanticValid: false, protocolAccepted: false, schemaErrors, semanticErrors: [], errors: schemaErrors, warnings };
  semanticErrors.push(...validateDisplayInvariants(json));

  if (Array.isArray(json.content_blocks) && json.content_blocks.length > 0) {
    const firstBlock = json.content_blocks[0];
    if (firstBlock.type !== 'text') semanticErrors.push(`[Rule #10] First content block must be type="text". Received: "${firstBlock.type}"`);
    if (firstBlock.level && firstBlock.level !== 'none') semanticErrors.push(`[Rule #10] First content block level must be "none". Received: "${firstBlock.level}"`);
    if (firstBlock.title && firstBlock.title.trim() !== '') semanticErrors.push(`[Rule #10] First content block title must be empty string. Received: "${firstBlock.title}"`);
  } else {
    semanticErrors.push('content_blocks must be a non-empty array');
  }

  if (json.interaction && typeof json.interaction === 'object') {
    const kind = json.interaction.kind;
    const inputType = json.interaction.input_type;
    const options = json.interaction.options || [];
    const recommendedActions = json.interaction.recommended_actions || [];
    if (inputType === 'ranked_select' && (!Array.isArray(options) || options.length < 3 || options.length > 6)) {
      semanticErrors.push(`[Invariant] ranked_select interaction requires between 3 and 6 options. Received: ${options.length}`);
    }
    if ((kind === 'question' || kind === 'handoff') && Array.isArray(recommendedActions) && recommendedActions.length > 0) {
      semanticErrors.push(`[Invariant] recommended_actions must be empty [] when interaction.kind is "${kind}". Received: ${recommendedActions.length} actions`);
    }
    if (kind === 'handoff' && json.rmo_readiness && typeof json.rmo_readiness === 'object') {
      const missingInputs: string[] = json.rmo_readiness.missing_inputs || [];
      const fieldIds = (json.interaction.fields || []).map((f: any) => f.id);
      for (const m of missingInputs) if (!fieldIds.includes(m)) warnings.push(`[Handoff] rmo_readiness.missing_input "${m}" not present in interaction.fields`);
    }
  }

  if (json.state?.progress) {
    const prog = json.state.progress;
    if (prog.active_security_penalty && prog.active_security_penalty !== '' && prog.security_breach_count === 0) {
      warnings.push(`[Invariant] active_security_penalty="${prog.active_security_penalty}" requires security_breach_count >= 1, but got security_breach_count=0`);
    }
  }

  semanticErrors.push(...confidenceErrors(json, true));

  if (serviceObj?.actions && Array.isArray(serviceObj.actions)) {
    for (const act of serviceObj.actions) {
      const actId = act.action_id || act.id;
      if (actId && !TRUSTED_SERVICE_ACTIONS[actId]) warnings.push(`[Security] Service action_id "${actId}" is untrusted/unregistered in server registry`);
    }
  }

  const semanticValid = semanticErrors.length === 0;
  return { jsonParsed: true, schemaValid, semanticValid, protocolAccepted: schemaValid && semanticValid, schemaErrors, semanticErrors, errors: [...schemaErrors, ...semanticErrors], warnings };
}

function confidenceErrors(json: any, verbose: boolean): string[] {
  const errors: string[] = [];
  const uc = json.state?.user_confidence;
  if (!uc) return errors;
  const { score, band } = uc;
  if (score === -1) {
    if (band !== 'unknown') errors.push(`[Confidence] When score is -1, band must be "unknown". Received: "${band}"`);
    if (uc.evidence_strength !== 'none') errors.push(verbose ? `[Confidence] When score is -1, evidence_strength must be "none". Received: "${uc.evidence_strength}"` : `[Confidence] When score is -1, evidence_strength must be "none".`);
  } else if (typeof score === 'number' && score >= 0 && score <= 100) {
    if (score <= 39 && band !== 'low') errors.push(`[Confidence] Score ${score} (0-39) requires band="low". Received: "${band}"`);
    else if (score >= 40 && score <= 69 && band !== 'medium') errors.push(`[Confidence] Score ${score} (40-69) requires band="medium". Received: "${band}"`);
    else if (score >= 70 && score <= 100 && band !== 'high') errors.push(`[Confidence] Score ${score} (70-100) requires band="high". Received: "${band}"`);
  } else {
    errors.push(`[Confidence] score must be -1 or integer 0..100. Received: ${score}`);
  }
  return errors;
}

export function validateProtocolV14(json: any): ExtendedProtocolValidationResult {
  const semanticErrors: string[] = [];
  const warnings: string[] = [];
  if (!json || typeof json !== 'object' || Array.isArray(json)) return { ...NOT_OBJECT };

  const ajvErrors = schemaErrorsFor(SCHEMA_V14, json);
  const schemaErrors: string[] = [...ajvErrors];
  if (json.schema_version !== '1.4') schemaErrors.push(`Invalid schema_version: expected "1.4", received "${json.schema_version}"`);
  if (!Array.isArray(json.content_blocks)) schemaErrors.push('content_blocks must be an array');
  if (!json.interaction || typeof json.interaction !== 'object') schemaErrors.push('interaction object is required');
  if (!json.service_trigger || typeof json.service_trigger !== 'object') schemaErrors.push('service_trigger object is required in v1.4 envelope');
  if (!json.rmo_readiness || typeof json.rmo_readiness !== 'object') schemaErrors.push('rmo_readiness object is required in v1.4 envelope');
  if (!json.state || typeof json.state !== 'object') schemaErrors.push('state object is required');
  if (!json.followups || typeof json.followups !== 'object') schemaErrors.push('followups object is required');

  const schemaValid = ajvErrors.length === 0 && schemaErrors.length === 0;
  if (!schemaValid) return { jsonParsed: true, schemaValid: false, semanticValid: false, protocolAccepted: false, schemaErrors, semanticErrors: [], errors: schemaErrors, warnings };
  semanticErrors.push(...validateDisplayInvariants(json));

  if (Array.isArray(json.content_blocks) && json.content_blocks.length > 0) {
    const first = json.content_blocks[0];
    if (first.type !== 'text') semanticErrors.push(`[Rule #10] First content block must be type="text". Received: "${first.type}"`);
    if (first.level && first.level !== 'none') semanticErrors.push(`[Rule #10] First content block level must be "none".`);
    if (first.title && first.title.trim() !== '') semanticErrors.push(`[Rule #10] First content block title must be empty string.`);
  } else {
    semanticErrors.push('content_blocks must be a non-empty array');
  }

  if (json.interaction && typeof json.interaction === 'object') {
    const { kind, input_type: inputType, options = [], recommended_actions = [] } = json.interaction;
    if (inputType === 'ranked_select' && (!Array.isArray(options) || options.length < 3 || options.length > 6)) {
      semanticErrors.push(`[Invariant] ranked_select requires 3-6 options. Received: ${options.length}`);
    }
    if ((kind === 'question' || kind === 'handoff') && Array.isArray(recommended_actions) && recommended_actions.length > 0) {
      semanticErrors.push(`[Invariant] recommended_actions must be empty [] when interaction.kind is "${kind}".`);
    }
    if (kind === 'handoff' && json.rmo_readiness) {
      const missingInputs: string[] = json.rmo_readiness.missing_inputs || [];
      const fieldIds = (json.interaction.fields || []).map((f: any) => f.id);
      for (const m of missingInputs) if (!fieldIds.includes(m)) warnings.push(`[Handoff] rmo_readiness.missing_input "${m}" not present in interaction.fields`);
    }
  }

  if (json.state?.progress) {
    const prog = json.state.progress;
    if (prog.active_security_penalty && prog.active_security_penalty !== '' && prog.security_breach_count === 0) {
      warnings.push(`[Invariant] active_security_penalty="${prog.active_security_penalty}" requires security_breach_count >= 1, but got security_breach_count=0`);
    }
  }

  semanticErrors.push(...confidenceErrors(json, false));

  if (Array.isArray(json.content_blocks)) {
    for (const block of json.content_blocks) {
      const d = block.data || {};
      if (block.type === 'flow') {
        const rawNodes: any[] = d.nodes || [];
        const nodeIds = new Set(rawNodes.map((n: any) => n.id));
        if (nodeIds.size !== rawNodes.length) {
          const seen = new Set<string>();
          for (const n of rawNodes) {
            if (seen.has(n.id)) semanticErrors.push(`[flow] Duplicate node id "${n.id}" in block "${block.id}"`);
            seen.add(n.id);
          }
        }
        for (const edge of (d.edges || [])) {
          if (edge.from && !nodeIds.has(edge.from)) warnings.push(`[flow] Edge "from" id "${edge.from}" does not reference a known node`);
          if (edge.to && !nodeIds.has(edge.to)) warnings.push(`[flow] Edge "to" id "${edge.to}" does not reference a known node`);
        }
      }
      if (block.type === 'pathway_map') {
        const rawLanes: any[] = d.lanes || [];
        if (new Set(rawLanes.map((l: any) => l.id)).size !== rawLanes.length) semanticErrors.push(`[pathway_map] Duplicate lane ids in block "${block.id}"`);
        for (const lane of rawLanes) {
          const steps: any[] = lane.steps || [];
          if (new Set(steps.map((s: any) => s.id)).size !== steps.length) semanticErrors.push(`[pathway_map] Duplicate step ids in lane "${lane.id}"`);
        }
      }
      if (block.type === 'timeline') {
        const rawMs: any[] = d.milestones || [];
        if (new Set(rawMs.map((m: any) => m.id)).size !== rawMs.length) semanticErrors.push(`[timeline] Duplicate milestone ids in block "${block.id}"`);
        const validStatuses = new Set(['completed', 'current', 'upcoming', 'blocked', 'paused', 'unknown']);
        for (const m of rawMs) if (m.status && !validStatuses.has(m.status)) semanticErrors.push(`[timeline] Invalid milestone status "${m.status}" in block "${block.id}"`);
      }
      if (block.type === 'scorecard') {
        for (const m of (d.metrics || [])) {
          if (['number', 'percentage', 'rating'].includes(m.value_type)) {
            if (typeof m.value !== 'number') semanticErrors.push(`[scorecard] Metric "${m.id}" value must be a number when value_type="${m.value_type}"`);
            if (m.max !== undefined && m.max !== null && typeof m.max !== 'number') semanticErrors.push(`[scorecard] Metric "${m.id}" max must be a number`);
          }
        }
      }
      if (block.type === 'chart') {
        const catLen = (d.categories || []).length;
        const allowedTypes = ['bar', 'line', 'donut', 'funnel'];
        if (d.chart_type && !allowedTypes.includes(d.chart_type)) semanticErrors.push(`[chart] chart_type "${d.chart_type}" not in allowed set [${allowedTypes.join(',')}]`);
        for (const s of (d.series || [])) {
          if (Array.isArray(s.values)) {
            if (s.values.length !== catLen) semanticErrors.push(`[chart] Series "${s.id}" values length (${s.values.length}) must match categories length (${catLen})`);
            for (const v of s.values) if (typeof v !== 'number') semanticErrors.push(`[chart] Series "${s.id}" contains non-numeric value: ${JSON.stringify(v)}`);
          }
        }
      }
      if (block.type === 'progress') {
        const currentCount = (d.stages || []).filter((s: any) => s.status === 'current').length;
        if (currentCount > 1) semanticErrors.push(`[progress] At most one stage may have status="current". Found ${currentCount}`);
      }
    }
  }

  if (json.service_trigger?.actions && Array.isArray(json.service_trigger.actions)) {
    for (const act of json.service_trigger.actions) {
      const actId = act.action_id || act.id;
      if (actId && !TRUSTED_SERVICE_ACTIONS[actId]) warnings.push(`[Security] service_trigger action_id "${actId}" is untrusted/unregistered in server registry`);
    }
  }

  const semanticValid = semanticErrors.length === 0;
  return { jsonParsed: true, schemaValid, semanticValid, protocolAccepted: schemaValid && semanticValid, schemaErrors, semanticErrors, errors: [...schemaErrors, ...semanticErrors], warnings };
}

/** Version-agnostic dispatcher: routes to v1.3 or v1.4 validator based on schema_version. */
export function validateProtocol(json: any): ExtendedProtocolValidationResult {
  if (json?.schema_version === '1.4') return validateProtocolV14(json);
  return validateProtocolV13(json);
}

/**
 * Validation of an incoming UserEvent against the trusted active interaction:
 * question_id agreement, valid options, ranked bounds and field schema.
 */
export function validateUserEventAgainstActiveInteraction(userEvent: any, activeInteraction: any): { valid: boolean; errors: string[] } {
  if (!userEvent) return { valid: true, errors: [] };

  const interaction: any =
    userEvent.interaction ||
    (userEvent.userEvent ? userEvent.userEvent.interaction : undefined) ||
    (userEvent.type
      ? {
          question_id: userEvent.interaction_id || 'active_question',
          selected_option_ids: userEvent.option_id ? [userEvent.option_id] : (userEvent.selected_option_ids || undefined),
          ranked_option_ids: userEvent.ranked_ids || userEvent.ranked_option_ids,
          fields: userEvent.fields,
          self_input: userEvent.value || userEvent.self_input,
          action_id: userEvent.action_id,
        }
      : undefined);

  if (!interaction || (!interaction.question_id && !interaction.action_id && !interaction.selected_option_ids && !interaction.ranked_option_ids && !interaction.fields && !interaction.self_input)) {
    return { valid: true, errors: [] };
  }

  const errors: string[] = [];
  for (const name of ['selected_option_ids', 'ranked_option_ids']) {
    if (interaction[name] !== undefined && (!Array.isArray(interaction[name]) || !interaction[name].every((v: unknown) => typeof v === 'string'))) return { valid: false, errors: ['Choices must be a list of valid option IDs.'] };
  }
  if (interaction.self_input !== undefined && typeof interaction.self_input !== 'string') return { valid: false, errors: ['Your answer must be text.'] };

  if (interaction.action_id) {
    if (!TRUSTED_SERVICE_ACTIONS[interaction.action_id]) errors.push(`Action ID "${interaction.action_id}" is not a recognized or trusted service action.`);
    return { valid: errors.length === 0, errors };
  }

  if (!activeInteraction || activeInteraction.kind === 'none') {
    if (interaction.question_id) errors.push(`No active question interaction on server. Received structured event for question_id "${interaction.question_id}".`);
    return { valid: errors.length === 0, errors };
  }

  const activeQId = activeInteraction.question_id || activeInteraction.id;
  if (interaction.question_id && activeQId && interaction.question_id !== activeQId) {
    errors.push(`Question ID mismatch: received "${interaction.question_id}", but active server question is "${activeQId}".`);
  }

  const trustedOptions = Array.isArray(activeInteraction.options) ? activeInteraction.options : [];
  const trustedOptionIds = trustedOptions.map((o: any) => o.id || o.option_id || o.value);
  const inputType = activeInteraction.input_type || 'single_select';

  if (inputType === 'single_select') {
    const selected: string[] = interaction.selected_option_ids || [];
    const hasSelfInput = !!(interaction.self_input && interaction.self_input.trim().length > 0);
    if (selected.length > 1) errors.push(`Single select interaction accepts at most 1 option, received ${selected.length}.`);
    if (selected.length === 0 && !hasSelfInput) errors.push(`Single select interaction requires exactly one valid option or permitted self_input.`);
    if (selected.length === 1 && hasSelfInput) errors.push(`Cannot submit both a selected option and self-input in single select.`);
    for (const optId of selected) if (!trustedOptionIds.includes(optId)) errors.push(`Selected option ID "${optId}" is not in the trusted active options list.`);
    if (hasSelfInput && !activeInteraction.allow_other_input) errors.push(`Self-input provided but allow_other_input is false for this question.`);
  }

  if (inputType === 'multi_select') {
    const selected: string[] = interaction.selected_option_ids || [];
    if (!selected.length && !interaction.self_input?.trim()) errors.push('Choose at least one option or enter your own answer.');
    if (new Set(selected).size !== selected.length) errors.push(`Duplicate option IDs submitted in multi-select.`);
    for (const optId of selected) if (!trustedOptionIds.includes(optId)) errors.push(`Selected option ID "${optId}" is not in the trusted active options list.`);
    if (interaction.self_input && interaction.self_input.trim().length > 0 && !activeInteraction.allow_other_input) errors.push(`Self-input provided but allow_other_input is false for this question.`);
  }

  if (inputType === 'ranked_select') {
    const ranked: string[] = interaction.ranked_option_ids || [];
    if (new Set(ranked).size !== ranked.length) errors.push(`Duplicate option IDs found in ranked selection.`);
    for (const optId of ranked) if (!trustedOptionIds.includes(optId)) errors.push(`Ranked option ID "${optId}" is not in the trusted active options list.`);
    const minOptions = Math.min(3, trustedOptions.length);
    const maxOptions = Math.min(6, trustedOptions.length);
    if (ranked.length < minOptions || ranked.length > maxOptions) errors.push(`Ranked options count (${ranked.length}) must be between ${minOptions} and ${maxOptions}.`);
  }

  if (inputType === 'fields' || activeInteraction.kind === 'handoff') {
    errors.push(...validateInteractionFields(activeInteraction, interaction.fields).errors);
  }
  if (inputType === 'text' && (typeof interaction.self_input !== 'string' || !interaction.self_input.trim())) {
    errors.push('Enter your answer before continuing.');
  }

  return { valid: errors.length === 0, errors };
}

export function validateInteractionFields(active: any, values: unknown): { valid: boolean; errors: string[]; fieldErrors: Record<string, string> } {
  const fieldErrors: Record<string, string> = {};
  const fields = Array.isArray(active?.fields) ? active.fields : [];
  if (!values || typeof values !== 'object' || Array.isArray(values)) { fieldErrors['form'] = 'Enter the requested details.'; values = {}; }
  const submitted = values as Record<string, unknown>;
  for (const key of Object.keys(submitted)) if (!fields.some((f: any) => f.id === key)) fieldErrors[key] = 'This field is not part of the current question.';
  for (const field of fields) {
    const value = submitted[field.id];
    if (value !== undefined && typeof value !== 'string') { fieldErrors[field.id] = `${field.label} must be text.`; continue; }
    const text = typeof value === 'string' ? value.trim() : '';
    if (field.required && !text) fieldErrors[field.id] = `Enter ${field.id === 'location' ? 'a city, suburb or postcode' : field.label.toLowerCase()}.`;
    else if (text.length > 500) fieldErrors[field.id] = 'Keep this answer under 500 characters.';
    else if (text && field.input_type === 'single_select' && !(field.options || []).some((o: any) => (o.value || o.label) === text)) fieldErrors[field.id] = `Choose one of the listed options for ${field.label.toLowerCase()}.`;
  }
  return { valid: Object.keys(fieldErrors).length === 0, errors: Object.values(fieldErrors), fieldErrors };
}

function validateDisplayInvariants(json: any): string[] {
  const errors: string[] = [];
  const inter = json.interaction;
  if (inter?.kind !== 'none') {
    if (!inter?.question_id?.trim() || !inter?.question?.trim()) errors.push('An active question needs an ID and clear question text.');
    const ids = (inter?.options || []).map((o: any) => o.id);
    if (ids.some((id: string) => !id?.trim()) || new Set(ids).size !== ids.length) errors.push('Question option IDs must be nonempty and unique.');
    if (inter?.input_type === 'single_select' && (ids.length < 2 || ids.length > 5)) errors.push('Single-choice questions need 2–5 options.');
    if (inter?.input_type === 'multi_select' && (ids.length < 2 || ids.length > 6)) errors.push('Multiple-choice questions need 2–6 options.');
    if (inter?.input_type === 'fields') {
      const fieldIds = inter.fields.map((f: any) => f.id);
      if (!fieldIds.length || new Set(fieldIds).size !== fieldIds.length) errors.push('A form needs nonempty, unique fields.');
      for (const f of inter.fields) {
        if (f.id === 'location' && (f.input_type === 'single_select' || f.options.length)) errors.push('Location must be a typed field without location choices.');
        if (f.input_type === 'single_select' && !f.options.length) errors.push('A selection field needs choices.');
      }
    }
    if (inter?.kind === 'handoff' && inter?.input_type !== 'fields') errors.push('A handoff must use fields.');
    if (inter?.kind === 'question' && !['text', 'single_select', 'multi_select', 'ranked_select'].includes(inter.input_type)) errors.push('A question must have an answer control.');
  }
  if (inter?.kind === 'none' && inter.input_type !== 'none') errors.push('Inactive questions must not expose answer controls.');
  for (const block of json.content_blocks || []) {
    if (block.type === 'table' || block.type === 'comparison') {
      const keys = block.columns.map((c: any) => c.key);
      if (!keys.length || new Set(keys).size !== keys.length) errors.push('Comparison columns must be nonempty and unique.');
      for (const row of block.rows) {
        const cells = row.cells.map((c: any) => c.key);
        if (cells.length !== keys.length || new Set(cells).size !== cells.length || cells.some((k: string) => !keys.includes(k))) errors.push('Every comparison row must preserve one cell per column.');
      }
    }
  }
  return errors;
}
