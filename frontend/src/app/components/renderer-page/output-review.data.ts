/**
 * Verbatim port of the original app's designed Renderer-page examples:
 *   src/ux/fixtures.ts (makeResponse, experienceFixtures)
 *   src/ux/scenarios.ts (uxScenarios)
 *   src/ux/outputReview.ts (outputReviewScenarios, outputReviewIssues)
 * Synthetic examples only — no AI calls or provider requests.
 */
/* eslint-disable */

export function makeResponse(overrides: Record<string, any> = {}): any {
  return {
    schema_version: '1.3',
    current_mode: 'A_CONVERSATION',
    response_intent: 'GENERAL_DELIVERY',
    content_blocks: [
      { id: 'b1', type: 'text', level: 'none', variant: 'default', title: '', text: 'Hello', items: [], columns: [], rows: [] },
    ],
    interaction: {
      kind: 'none', input_type: 'none', question_id: '', question: '', options: [],
      allow_other_input: false, other_input_label: '', fields: [], recommended_actions: [],
    },
    service_trigger: {
      service_intent_detected: false,
      primary_requested_service: 'NONE',
      confidence: 'LOW',
      reason: 'No service intent',
      trigger_now: false,
      needs_more_clarity: false,
      actions: [],
    },
    rmo_readiness: {
      readiness: 'NOT_READY',
      ready_to_generate: false,
      missing_inputs: [],
      verification_required: false,
    },
    state: {
      active_response_mode: 'Standard',
      effective_response_mode: 'Standard',
      mode_source: 'default',
      safety_override_applied: false,
      user_confidence: { score: -1, band: 'unknown', evidence_strength: 'none', trend: 'unknown', reason_codes: [] },
      progress: { explained: false, failed_attempts: 0, loop_count_same_issue: 0, security_breach_count: 0, active_security_penalty: '' },
    },
    followups: { enabled: false, cancel_on_user_message: true, topic_lock: false, topic_key: '', triggers: [] },
    ...overrides,
  };
}


const option = (id: string, label: string) => ({id,label,value:label,description:''});
const block = (type: string, title: string, text: string) => ({id:type,type,level:'none',variant:'default',title,text,items:[],columns:[],rows:[]});
const base = makeResponse();
const question = (input_type: string, question: string, options: any[] = []) => ({...base.interaction,kind:'question',input_type,question_id:'example-'+input_type,question,options,allow_other_input:['single_select','multi_select'].includes(input_type),other_input_label:'Something else'});
export const experienceFixtures: {id:string;label:string;response:any}[] = [
 {id:'text',label:'Start exploring',response:makeResponse({content_blocks:[block('text','',"You do not need to choose a career today. We can start with one thing you enjoy and try a small activity.")],interaction:question('text','What is one activity you enjoy, at school, work or home?')})},
 {id:'single',label:'Choose one direction',response:makeResponse({content_blocks:[block('text','',"We can start wherever feels useful to you. You can change direction later.")],interaction:question('single_select','What would you like help with first?',[option('explore','Explore work I might enjoy'),option('learn','Learn a useful skill'),option('unsure','I’m not sure yet')])})},
 {id:'multi',label:'Fit around daily life',response:makeResponse({content_blocks:[block('text','',"Let’s fit your next step around your daily life. Select only what matters to you.")],interaction:question('multi_select','What does your next step need to fit around?',[option('hours','Work hours'),option('family','Caring for someone'),option('cost','A limited budget')])})},
 {id:'rank',label:'Put priorities in order',response:makeResponse({content_blocks:[block('text','',"All three priorities can matter. Putting them in order helps us compare the trade-offs.")],interaction:question('ranked_select','Which matters most to you right now?',[option('income','Keeping my income'),option('hours','Flexible hours'),option('interest','Enjoying the work')])})},
 {id:'location',label:'Prepare a request',response:makeResponse({current_mode:'S_SERVICE_HANDOFF',content_blocks:[block('text','',"We can prepare a draft for cybersecurity training. Nothing will be sent: provider requests are not connected here. These details help describe the training you need.")],interaction:{...base.interaction,kind:'handoff',input_type:'fields',question_id:'example-location',question:'Where would you like to study?',fields:[{id:'location',label:'Location',input_type:'australian_location',required:true,options:[]},{id:'residency',label:'Residency for study',input_type:'single_select',required:true,options:[option('domestic','Domestic'),option('international','International')]}]},rmo_readiness:{readiness:'PARTIAL',ready_to_generate:false,missing_inputs:['location','residency'],verification_required:false}})},
 {id:'compare',label:'Compare three routes',response:makeResponse({current_mode:'B_DELIVERY',content_blocks:[block('text','',"You can explore a change while keeping your job. These are ways to test an interest; availability and costs still need checking."),{...block('comparison','Three ways to explore',''),columns:[{key:'study',label:'Part-time study'},{key:'trial',label:'Work trial'},{key:'self',label:'Self-study'}],rows:[{id:'time',criteria:'Time and flexibility',cells:[{key:'study',value:'Regular study blocks; check the timetable.'},{key:'trial',value:'Arrange a short visit or supervised activity.'},{key:'self',value:'Choose your own short sessions.'}]},{id:'learn',criteria:'What you can find out',cells:[{key:'study',value:'Whether you enjoy learning the subject.'},{key:'trial',value:'What some everyday tasks feel like.'},{key:'self',value:'Whether the topic keeps your interest.'}]},{id:'check',criteria:'Before you commit',cells:[{key:'study',value:'Check workload, cost and entry requirements.'},{key:'trial',value:'Check supervision, pay and what is allowed.'},{key:'self',value:'Choose one small task with a clear outcome.'}]}]}],interaction:{...base.interaction,recommended_actions:[{id:'small-plan',label:'Make a small plan',message:'Help me plan one small activity to try this week.'}]}})},
];

/** Reference-document coverage plus conversation and recovery cases. Synthetic examples only. */
export const uxScenarios = [
  ['school-undecided','School student, unsure','Find a low-pressure starting point','I am 15 and have no idea what work I want. I do not know what I am good at.','Offer small examples or an experiment; one easy question; no eligibility or skills assumptions.'],
  ['current-student','Currently studying','Explore a change without discarding progress','I am studying business but I like working outdoors. Help me see my options before I leave my course.','Compare continuation, a small trial and a change; no instruction to withdraw.'],
  ['near-completion','Nearing completion','Connect study to first practical experience','I finish my diploma soon and have never had a job. How can I get some practical experience?','Distinguish placement, internship and entry work; explain terms and do not promise payment.'],
  ['graduate','Recent graduate','Take a concrete first-job step','I recently graduated and keep getting no response to my applications. I need a manageable next step.','Suggest an observable application improvement; no guaranteed job.'],
  ['first-job','Unemployed, no experience','Identify an accessible starting route','I have never worked and want my first job. I am nervous about not having experience.','Recognise informal experience without claiming skills; small next action.'],
  ['basic-experience','Unemployed, basic experience','Use actual experience','I did a few months of retail work and am unemployed. I would like steadier work.','Clarify what steadier means; use stated experience only.'],
  ['new-field','Career change, no relevant experience','Try a field before committing','I want to change to animal care but have no experience in it. Where should I start?','Practical low-risk exploration; no qualification or placement guarantees.'],
  ['experienced-change','Experienced, changing field','Find transferable evidence','I worked in logistics for 12 years and want a different field. I am not sure which skills transfer.','Ask about actual tasks; explain transfer without equating tenure with competence.'],
  ['same-field','Experienced, same field','Prepare a focused job search','I lost my hospitality job and want another role in the same field. What should I do first?','Prioritise a small job-search plan; no career-change intake.'],
  ['employed-change','Employed career change','Explore while protecting income','I work in IT support and want a change but cannot lose my income. Help me compare a gradual move and retraining.','Balanced comparison; respect income constraint; avoid title-to-skill assumptions.'],
  ['upskill','Upskilling','Choose skills based on a work goal','I want to improve my spreadsheet skills for the admin work I already do.','Target practical tasks and a small learning goal; no unnecessary career change.'],
  ['leadership','Promotion and leadership','Prepare for a larger role','I want to move into team leadership. I have never managed anyone.','Suggest supervised practice and feedback; no invented mentorship service.'],
  ['lateral','Lateral move','Compare a move at the same level','I want to move from sales to customer success at the same level. I do not want a promotion.','Respect lateral goal; compare actual activities and evidence gaps.'],
  ['job-preparation','Preparing for a new job','Prepare for an interview','I have a job interview next week and need a simple preparation plan.','Short sequenced plan with practice and checkpoint.'],
  ['return-to-work','Return after caring break','Re-enter on stated terms','I am a mother returning after eight years caring for family. I can work during school hours and need income soon.','Use stated hours/income; value experience without assuming skills; avoid gender-based career choices.'],
  ['reduced-hours','Reduced hours at 55','Adjust work sustainably','I am 55 and want fewer working hours, but I am not ready to retire.','Respect continued work; compare reduced-hours routes; no retirement assumptions.'],
  ['specialist','Specialised development','Deepen expertise','I am an experienced mechanic and want to learn electric vehicle maintenance.','Identify training/practice needs; no claim of verified local courses.'],
  ['new-subject','Learn something new','Explore learning for interest','I want to learn photography for enjoyment, not a new career.','Respect enjoyment goal; no forced job or service pathway.'],
  ['business','Starting a business','Explore feasibility','I want to start a small home business but have no idea how to test the idea.','Suggest small demand test; business service availability not invented.'],
  ['staff','Employer training staff','Define a team learning need','I run a small cafe and want to train three staff in customer service.','Team goal, practice and observable outcomes; no invented employer service.'],
  ['parent-child','Parent asking for child','Keep the young person involved','I am asking for my 16-year-old daughter. I want nursing for her but she likes design. How should we explore this?','Distinguish parent and child preferences; invite daughter perspective without identifiers.'],
  ['access','Access and caring constraints','Find a workable learning format','I care for my father and have hearing loss. I can study three hours per week and need captions. Help me plan.','Honour hours and captions; explain checks without guaranteeing accessibility.'],
  ['simple','Plain-language request','Reduce reading effort','Please explain apprenticeships in very simple language. Reading long answers is hard for me.','Short plain explanation; avoid unexplained jargon and patronising tone.'],
  ['compare','Compare options','Make trade-offs visible','Compare part-time study, a short work trial, and self-study for exploring a new career while keeping my job. Use the same criteria for all three.','Preserve all three options and aligned criteria; distinguish advice from verified availability.'],
  ['plan','Four-week plan','Produce observable progress','Give me a four-week plan to try basic bookkeeping for five hours a week before deciding on a course. No referrals yet.','Weekly tasks, evidence of progress and decision checkpoint; no service form.'],
  ['handoff','Explicit education request','Prepare a truthful draft','Please prepare an education offer request for cybersecurity training. I have not given my location or residency. Do not send anything yet.','Explain draft/not sent; gather only missing operational fields; location must be typed.'],
  ['outside-au','Outside Australia','Clarify service scope','I live in Auckland, New Zealand, and want help finding local training. Can you submit an Australian education request for me?','Do not infer Australian residency or eligibility; explain scope and no submission.'],
  ['rpl','Prior learning','Explain assessment','I have years of construction experience but no certificate. What does recognition of prior learning mean?','Plain explanation of skills assessment; no automatic qualification or funding promise.'],
  ['paid-work-training','Paid work with training','Understand an apprenticeship','I need to earn while I learn. How is an apprenticeship different from a course or internship?','Distinguish models; no promise that every internship is paid or an opening exists.'],
  ['unavailable-service','Booking and reminder','Avoid false completion','Book me a career counsellor tomorrow and remind me next week.','Clearly cannot book or remind in this lab; practical alternative, no executed action.'],
  ['job-request','Job matching request','Prepare a job request','Please prepare a draft request for administrative job opportunities. Do not apply for me.','Classify job matching; draft only; ask only missing useful details.'],
  ['apprentice-request','Apprenticeship request','Explore paid work with training','Please help draft an apprenticeship request in electrical work. I have not given my location. Do not submit it.','Distinguish paid work with training from a course; no opening, acceptance or pay guarantee.'],
  ['trainee-request','Traineeship request','Prepare a training-and-work request','Please help draft a business administration traineeship request. Do not send it.','Use traineeship classification; explain draft status and relevant missing details.'],
  ['intern-request','Internship request','Prepare practical experience request','I am a university student. Please prepare an internship request in marketing, but do not send anything.','Internship classification; no assumption of payment or course credit.'],
  ['placement-request','Study placement request','Meet practical study needs','My course requires a work placement in community services. Help me prepare a request but do not send it.','Work-placement classification; distinguish course requirement from ordinary employment.'],
  ['grad-request','Graduate program request','Prepare a graduate opportunity request','I recently finished an engineering degree. Help me draft a graduate program request. Do not apply anywhere.','Graduate-program classification; entry rules and availability need checking.'],
  ['rpl-request','Prior-learning assessment request','Prepare skills evidence','Help me prepare a request for recognition of prior learning in hospitality. Keep it as a draft only.','Prior-learning classification; existing evidence needs provider assessment, not automatic credit.'],
  ['pathway-request','Career pathway request','Map a route','Help me create a career pathway from retail to office administration while I keep working.','Practical steps, proof and checkpoint; no need for a service form just to plan.'],
  ['admission-request','Direct admission request','Understand the application boundary','Please enrol me in a course now and tell me admission is confirmed.','Cannot enrol or confirm admission; no executed action or invented institution.'],
  ['residency-unsure','Unsure about a form answer','Avoid guessing eligibility','The form asks Domestic or International and I do not know which applies to me. I do not want to share documents.','Allow uncertainty; do not request documents or force a legal category; provider confirms later.'],
] as const;

export type ReviewScenario = {id:string;label:string;group:'Counselling'|'Boxes & inputs'|'Waiting & recovery';request:string;expected:string;response?:any;phase?:'waiting'|'thinking'|'receiving'|'checking';state?:'slow'|'stopped'|'invalid'|'earlier';issue?:string};
const b=(type:string,title:string,text='',items:any[]=[],data?:any)=>({id:type,type,level:type==='heading'?'h2':'none',variant:'default',title,text,items,columns:[],rows:[],...(data?{data}:{})});
const item=(id:string,title:string,text:string,status='')=>({id,title,text,status,value:''});
const q=(text:string)=>({...makeResponse().interaction,kind:'question',input_type:'text',question_id:'review-question',question:text});
const action=(label:string,message:string)=>({...makeResponse().interaction,recommended_actions:[{id:'review-next',label,message}]});
const designed:Record<string,[string,string?]> = {
'school-undecided':['You do not need a career choice yet. Try a ten-minute activity, such as sketching an idea, organising something or solving a puzzle. Notice what you enjoy.','Which of those feels easiest to try?'],
'current-student':['Before leaving your course, try a small outdoor activity and ask your course adviser what options would preserve your progress. You can explore without making a final decision.','What appeals to you about working outdoors?'],
'near-completion':['Start with one practical task related to your diploma. A placement may be part of study; an internship is work experience; an entry-level job is employment. Check duties, supervision and payment before agreeing.','What is your diploma in?'],
'graduate':['Choose one recent application. Compare your examples with the job’s requirements and improve one example so it shows what you did and the result.','Which requirement is hardest to demonstrate?'],
'first-job':['You can start with experience from school, home or volunteering. Describe one task you have actually done; we can look for evidence you could use in an application.','What is one task you have helped with?'],
'basic-experience':['Your retail experience gives us a starting point. “Steadier” could mean regular hours, more income or staying longer with one employer.','Which of those matters most?'],
'new-field':['Try learning about the everyday tasks first, then look for a supervised way to experience them. Check any training, safety or supervision requirements before committing.','Which part of animal care interests you?'],
'experienced-change':['Your years in logistics are useful context, but the tasks you performed tell us more about transferable skills. We can connect a real example to work in another field.','What is one logistics task you handled regularly?'],
'same-field':['Start by updating one recent work example and the hours you can offer. Then use those details to assess a suitable hospitality vacancy.','What kind of hospitality work do you want to continue?'],
'employed-change':['A gradual move can let you test new tasks while keeping your income. Retraining may build a specific skill, but cost and workload need checking. Neither route guarantees a job.','What type of work would you like to try?'],
'upskill':['Pick one spreadsheet task you already do, such as sorting records or checking totals. Practise it on sample data and check the result before using it at work.','Which task would you like to make easier?'],
'leadership':['Try a small, agreed responsibility such as coordinating one task, then ask for feedback. This gives you evidence of how you support people before taking on a management role.','Is there a small team task you could help coordinate?'],
'lateral':['A move to customer success could use some of your sales experience, depending on the actual duties. Compare customer follow-up, problem-solving and product knowledge against a real role description.','Which of those tasks have you done?'],
'job-preparation':['Choose two examples of work you have done. Practise explaining the situation, your action and the result. Then prepare one question about the role.','Which interview question would you like to practise?'],
'return-to-work':['Let’s use the school hours you said are available and your need for income. Start with roles whose advertised shifts fit, then check the actual schedule. Caring experience can provide examples, but we should describe what you did.','What kind of work would you like to return to?'],
'reduced-hours':['You want to keep working with fewer hours. You could explore reduced hours in your current role or compare roles with a suitable schedule. Availability and income will need checking.','How many hours would you like to work?'],
'specialist':['Start by identifying the electric vehicle tasks you want to perform, then check the training, supervision and safety requirements for those tasks. Your mechanic experience is relevant but does not establish every new competency.','Which task do you want to learn first?'],
'new-subject':['For enjoyment, start with one photo exercise: photograph the same object from three angles and choose the result you like. You do not need a career plan or a course to try this.'],
'business':['Write down one person’s problem your idea could solve. Ask a few potential customers how they handle it now before spending money. Their feedback can help you refine a small test.','What idea would you like to test?'],
'staff':['Pick one customer situation your three staff find difficult. Practise a response together and agree what a good result looks like. Review what changed after a week.','Which situation should the team practise?'],
'parent-child':['Your preference for nursing and your daughter’s interest in design are different starting points. Give her room to describe what she enjoys, then explore small activities in both areas without committing her.','What does she enjoy about design?'],
'access':['Let’s keep the plan within your three available hours and look for captioned materials. Start with a short sample lesson. Ask the provider to confirm captions and any live attendance before enrolling.','What would you like to learn?'],
'simple':['An apprenticeship combines paid work with training for a job. You learn by doing tasks at work and studying. The details depend on the apprenticeship.'],
'compare':['Compare the same factors for each route: time, cost, what you can try and what still needs checking. Keep your current job in the plan.'],
'plan':['Week 1: practise recording sample transactions. Week 2: sort them into categories. Week 3: check totals and fix errors. Week 4: review a small sample ledger and decide whether you want to learn more. Keep each week within five hours; use sample data.'],
'handoff':['We can prepare a draft describing the cybersecurity training you want. Nothing will be sent. Location and your declared study category help prepare the request; they do not confirm eligibility.'],
'outside-au':['You have said Auckland, New Zealand. I cannot submit a provider request here or assume Australian eligibility. We can prepare questions to help you explore local training.','What subject would you like to study?'],
'rpl':['Recognition of prior learning means a provider assesses evidence of what you already know and can do against a qualification’s requirements. Experience alone does not automatically give you a certificate.','What construction tasks could you show evidence for?'],
'paid-work-training':['An apprenticeship combines employment with training. A course focuses on learning and may include practical work. An internship offers work experience; payment and conditions need checking for the specific opportunity.'],
'unavailable-service':['I cannot book appointments or send reminders from this lab. You can contact a counsellor directly and add a reminder to your calendar. I can help you prepare a short message.'],
'job-request':['We can draft the kind of administrative work you want to explore. Nothing will be sent and no application will be made.','Where would you like to work?'],
'apprentice-request':['We can draft your interest in an electrical apprenticeship, which combines work and training. An employer and training provider would need to confirm openings and requirements. Nothing will be submitted.','Where would you like to work and train?'],
'trainee-request':['We can draft a business administration traineeship request. The work, training, entry requirements and availability will need confirmation. Nothing will be sent.','Where would you like to work and train?'],
'intern-request':['We can prepare a marketing internship draft. Payment, supervision and course credit need checking for each opportunity. Nothing will be sent.','What practical marketing tasks would you like to try?'],
'placement-request':['Your course requires a community services placement. Start with the course’s placement rules, required hours and supervision requirements so the draft describes the right experience. Nothing will be sent.','What placement requirements has your course provided?'],
'grad-request':['We can draft your interest in engineering graduate programs. Each program has its own entry rules and dates, which need checking. We will not apply anywhere.','Which engineering area would you like to explore?'],
'rpl-request':['A hospitality prior-learning draft can describe tasks you have performed and evidence you hold. A provider must assess that evidence; credit is not automatic. Nothing will be sent.','Which hospitality tasks could you demonstrate?'],
'pathway-request':['Start with an office task you could practise while keeping your retail work. Use a real example to identify what transfers, practise one gap and review your progress before choosing training.','Which office task interests you?'],
'admission-request':['I cannot enrol you or confirm admission here. The provider must check its requirements and accept your application. I can help you prepare the questions or information you need.'],
'residency-unsure':['You do not need to guess or share documents here. We can pause that part of the draft and continue exploring. Ask the provider which study category applies when you are ready.']
};
const counselling:ReviewScenario[]=uxScenarios.map(([id,label,goal,request,expected])=>{
 const [answer,question]=designed[id];
 let response=makeResponse({content_blocks:[b('text','',answer)],interaction:question?q(question):makeResponse().interaction});
 if(id==='compare')response=experienceFixtures.find(f=>f.id==='compare')!.response;
 if(id==='handoff')response=experienceFixtures.find(f=>f.id==='location')!.response;
 if(id==='plan')response=makeResponse({content_blocks:[{...b('text','','Here is a small trial you can review before choosing a course.'),id:'intro'},b('steps','A four-week trial','Use sample data. Allow no more than five hours each week.',[
 item('one','Week 1 · Record','Enter five sample transactions. Keep a clear record.','current'),item('two','Week 2 · Sort','Group transactions and explain your categories.','next'),item('three','Week 3 · Check','Check totals and record any corrections.','next'),item('four','Week 4 · Decide','Review your sample ledger. Note what you enjoyed and what was difficult.','next')])],interaction:action('Review my trial','Help me review what I learned from my bookkeeping trial.')});
 return {id,label,group:'Counselling',request,expected:goal+'. '+expected,response};
});
const layout=(id:string,label:string,block:any,expected:string,rich=false):ReviewScenario=>({id:'box-'+id,label,group:'Boxes & inputs',request:'Show '+label.toLowerCase()+'.',expected,response:makeResponse({schema_version:rich?'1.4':'1.3',...(rich?{rmo_readiness:{...makeResponse().rmo_readiness,verification_required:[]},state:{...makeResponse().state,progress:{explained:[],failed_attempts:0,loop_count_same_issue:0}}}:{}),content_blocks:[...(block.type!=='text'||block.title?[{...b('text','','Here is an example to help you consider your next step.'),id:'intro',...(rich?{data:{}}:{})}]:[]),{...block,...(rich?{data:block.data||{}}:{})}]})});
const formats:ReviewScenario[]=[
 layout('heading','Section heading',b('heading','Your next step','A small start'),'Readable heading; no repeated title.'),
 layout('text','Direct answer',b('text','','Could you confirm whether this unit has compulsory live sessions, and when they run?'),'When asked for one question, deliver one question without adding another form.'),
 layout('list','Short list',b('list','Before you decide','',[item('a','Time','Check scheduled attendance.'),item('b','Cost','Check the full cost.'),item('c','Support','Ask about the support you need.')]),'Three distinct checks; no implied confirmation.'),
 layout('steps','Action plan',b('steps','Try, review, decide','',[item('a','Try one task','Spend 20 minutes on sample data.','current'),item('b','Review','Note what you enjoyed.','next'),item('c','Decide','Choose whether to continue.','next')]),'Sequence, effort and a decision point; a plan is not proof of completion.'),
 layout('table','Facts with gaps',{...b('table','What we know'),columns:[{key:'factor',label:'Detail'},{key:'value',label:'Information'}],rows:[{id:'a',cells:[{key:'factor',value:'Available study time'},{key:'value',value:'12 hours per week · You told us'}]},{id:'b',cells:[{key:'factor',value:'Required attendance'},{key:'value',value:''}]}]},'Missing information reads “Not provided”; columns remain labelled on a phone.'),
 {...experienceFixtures.find(f=>f.id==='compare')!,id:'box-comparison',group:'Boxes & inputs',request:'Compare three routes.',expected:'Same criteria for every option; no dropped columns.'},
 layout('key_value','Personal summary',b('key_value','Details you gave us','',[{...item('a','Location','Geelong, Victoria'),value:'Geelong, Victoria'},{...item('b','Availability','12 hours each week, with evenings unavailable'),value:'12 hours each week, with evenings unavailable'}]),'Long values wrap; no inferred availability.'),
 ...['default','info','success','warning','danger','muted'].map(variant=>layout('callout-'+variant,'Notice · '+variant,{...b('callout',variant==='success'?'Detail updated':'Before you commit',variant==='success'?'Your draft now says 12 hours per week. Nothing has been sent.':'Course attendance has not been confirmed. Check the current unit guide or ask the provider.'),variant},'Meaning is expressed in words, not colour alone.')),
 layout('cards','Two route cards',b('cards','Two ways to try', 'Illustrative routes; availability is not checked.',[],{cards:['Guided learning','Independent practice'].map((title,i)=>({id:'c'+i,title,subtitle:'A way to explore',description:i?'Practise a small task at your own pace.':'Try a sample lesson with feedback.',status:'neutral',badge:'Example',facts:[{label:'Time',value:'Choose a short trial'},{label:'Before committing',value:'Check cost and support'}]}))}),'Cards share the same facts and remain readable on a phone.',true),
 layout('timeline','Timeline',b('timeline','A suggested sequence','',[],{milestones:['Try a task','Review your experience','Decide the next step'].map((label,i)=>({id:'m'+i,label,description:'Adjust this plan to suit your week.',time_label:'Step '+(i+1),status:i?'upcoming':'current',optional:false}))}),'No invented dates or completed bookings.',true),
 layout('flow','Decision branches',b('flow','Choose based on what you learn','',[],{nodes:[{id:'node_a',label:'Try a small task',description:'Notice your interest and effort.',node_type:'experience',status:'current'},{id:'node_b',label:'Explore training',description:'Check the gap you want to learn.',node_type:'training',status:'upcoming'}],edges:[{from:'node_a',to:'node_b',label:'If you want to continue',condition:'After reviewing the trial'}]}),'Connections show human-readable step names, never internal IDs.',true),
 layout('pathway','Alternative pathways',b('pathway_map','Possible next steps','',[],{goal:'Explore administration while working',lanes:[{id:'l1',title:'Practise first',summary:'A small commitment',recommended:false,steps:[{id:'s1',label:'Try a spreadsheet task',description:'Use sample data.',status:'current'}]},{id:'l2',title:'Ask about a short course',summary:'Check whether it meets your gap',recommended:false,steps:[{id:'s2',label:'Check content and attendance',description:'Ask the provider.',status:'upcoming'}]}]}),'Alternatives have trade-offs; no automatic preferred route.',true),
 layout('scorecard','Numbers with context',b('scorecard','Your planning figures','',[],{metrics:[{id:'hours',label:'Time you offered',value:12,value_type:'number',unit:'hours/week',max:null,status:'neutral',trend:'unknown',description:'User-provided. Does not establish that the course fits.'},{id:'fit',label:'Course fit',value:'Not checked',value_type:'text',unit:'',max:null,status:'neutral',trend:'unknown',description:'Attendance and peak workload still need checking.'}]}),'A stated number is not a suitability score or a success probability.',true),
 ...['bar','line','donut','funnel'].map(chart_type=>layout('chart-'+chart_type,'Data · '+chart_type,b('chart','Two trial weeks','Illustrative user-entered figures, not course workload advice.',[],{chart_type,categories:['Week 1','Week 2'],series:[{id:'planned',label:'Planned',values:[3,4],unit:'hours'},{id:'actual',label:'Used',values:[0,2],unit:'hours'}],source_status:'provided'}),'Every series, unit and zero is visible. A labelled table is used for reliable comparison.',true)),
 layout('progress','Plan progress',b('progress','Your plan','',[],{stages:[{id:'p1',label:'Describe what matters',status:'completed',description:'Recorded in this draft.'},{id:'p2',label:'Check the details',status:'current',description:'Attendance still needs confirming.'},{id:'p3',label:'Decide whether to proceed',status:'upcoming',description:'No provider request has been sent.'}]}),'Labels and explanations remain readable; progress refers to the plan, not external execution.',true),
 ...experienceFixtures.filter(f=>!['compare'].includes(f.id)).map(f=>({...f,id:'input-'+f.id,group:'Boxes & inputs' as const,request:'Try this question.',expected:'Enter an answer, submit once, and test a failed reply. A failed reply must preserve the answer.'}))
];
const edgeCases:ReviewScenario[]=[
 {id:'missing-data',label:'Not enough evidence',group:'Counselling',request:'Does this course fit my 12 available hours?',expected:'Do not turn a workload estimate into a fit verdict. Identify the missing fact and offer research.',response:makeResponse({content_blocks:[b('text','','You have 12 hours available each week. I cannot confirm that this course fits without its scheduled attendance and expected workload, including assessments.'),{...b('callout','What still needs checking','The current unit guide, compulsory sessions and peak assessment weeks.'),variant:'warning'}],interaction:action('Check attendance','Help me find current attendance requirements for this course.')})},
 {id:'conflicting-data',label:'Sources disagree',group:'Counselling',request:'One page says online and another says campus. Which is right?',expected:'Show the disagreement and relevant scope; no invented resolution.',response:makeResponse({content_blocks:[b('text','','Those descriptions may refer to different units, intakes or delivery modes. We have not established which applies to your choice.'),{...b('callout','Before deciding','Check the exact course, unit, intake and campus with the provider.'),variant:'warning'}],interaction:q('Which course and intake are you checking?')})},
 {id:'changed-detail',label:'User corrects availability',group:'Counselling',request:'I can study 6 hours, not 12. Keep evenings free.',expected:'Replace the old constraint, preserve the rest and reconsider the plan.',response:makeResponse({content_blocks:[b('text','','I have updated the plan to six hours per week, with evenings unavailable. The earlier 12-hour assumption no longer applies. We should check workload against your updated limit before choosing a study load.')],interaction:action('Recheck the plan','Review the plan using six hours per week and no evenings.')})},
 {id:'no-more-questions',label:'User asks to stop questions',group:'Counselling',request:'Give me just one question to ask the provider. No follow-up.',expected:'One requested sentence, no intake question or extra action.',response:makeResponse({content_blocks:[b('text','','Does this unit require live attendance, and if so, on which days and at what times?')]})},
 {id:'pause',label:'Pause or change direction',group:'Counselling',request:'Stop the course request. I only want to explore.',expected:'End the intake; do not keep asking for location or residency.',response:makeResponse({response_intent:'PAUSE_CLOSURE',content_blocks:[b('text','','The draft request is paused. Nothing has been sent. You can explore another direction whenever you are ready.')]})}
];
const lifecycle:ReviewScenario[]=[
 ...(['waiting','thinking','receiving','checking'] as const).map(phase=>({id:'state-'+phase,label:phase==='thinking'?'Considering the question':phase==='receiving'?'Receiving the answer':phase==='checking'?'Checking display format':'Waiting for Gemini',group:'Waiting & recovery' as const,request:'Send a question.',expected:'Compact rotating title, short subtext and Stop. No raw reasoning or unfinished JSON.',phase})),
 {id:'state-slow',label:'Slow response',group:'Waiting & recovery',request:'The response takes longer.',expected:'Explain the delay; keep Stop available without claiming fake progress.',phase:'waiting',state:'slow'},
 {id:'state-stopped',label:'Stopped response',group:'Waiting & recovery',request:'Stop generation.',expected:'Keep the question draft; offer retry. Do not treat partial JSON as a final answer.',state:'stopped'},
 {id:'state-invalid',label:'Invalid / unsupported output',group:'Waiting & recovery',request:'The model returns an unsupported shape.',expected:'Show a plain error and retry; never expose JSON. Checklist blocks are currently outside both supported schemas.',state:'invalid'},
 {id:'state-earlier',label:'Earlier question',group:'Waiting & recovery',request:'Scroll back to an older question.',expected:'Show the earlier question without active stale inputs.',state:'earlier',response:experienceFixtures.find(f=>f.id==='single')!.response}
];
export const outputReviewScenarios:ReviewScenario[]=[...counselling,...edgeCases,...formats,...lifecycle];
export const outputReviewIssues=[
 {status:'Fixed',title:'Charts silently omitted extra series',detail:'All chart formats now display every series, category, unit and zero in a labelled table.'},
 {status:'Fixed',title:'Rich content was missing from copy and speech',detail:'The readable projection now includes card facts, pathways, timelines, metrics and chart data.'},
 {status:'Fixed',title:'Flow connections exposed internal IDs',detail:'Connections now use the visible step names.'},
 {status:'Fixed',title:'Dense cards used very small text',detail:'Rich layouts now use larger text, wrap long values and keep progress explanations in a vertical list.'},
 {status:'Partly improved',title:'A polished answer can contain unverified advice',detail:'A focused Gemini retest respected the one-question request and stopped claiming that 12 study hours fit a unit. Accessibility advice still implied short modules prevent overload without workload evidence. This remains open.'},
 {status:'Known limit',title:'No proof of provider eligibility or course suitability',detail:'Schema validation checks the output format. It does not verify advice. Explore more has separate sourced research; automatic evidence checks for every main-chat recommendation are not yet connected.'},
 {status:'Known limit',title:'Checklist is not an accepted output type',detail:'A dormant renderer showed every item with a tick. The fallback is corrected, but the main contract still rejects this type. Use a list or steps.'},
 {status:'Needs user testing',title:'Readability across ages is not yet proven',detail:'Phone layout and interactions can be checked here. Comprehension needs sessions with younger users, adults, parents and people with access needs.'},
 {status:'Known limit',title:'Provider actions are draft-only',detail:'Forms and buttons cannot book, enrol, submit an application or establish an entitlement.'}
];
