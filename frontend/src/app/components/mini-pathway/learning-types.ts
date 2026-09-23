/** Presentation catalogue: describes named learning types, never verifies a course or admission.
 * Verbatim port of the original miniPathway/learningTypes.ts. */
export type LearningType={id:string;label:string;level:string;tone:string;explanation:string;check:string};
const definitions:Array<LearningType & {pattern:RegExp}>=[
 {id:'gradcert',label:'Graduate Certificate',level:'AQF 8',tone:'level8',pattern:/\bgraduate certificate\b/gi,explanation:'Focused advanced learning for professional or highly skilled work.',check:'Check admission requirements, assumed knowledge and whether it offers credit into further study.'},
 {id:'graddip',label:'Graduate Diploma',level:'AQF 8',tone:'level8',pattern:/\bgraduate diploma\b/gi,explanation:'Advanced study in a specialised field. Sharing a level with another qualification does not make the courses interchangeable.',check:'Compare content, entry requirements and the particular outcome you need.'},
 {id:'advanced',label:'Advanced Diploma',level:'AQF 6',tone:'level6',pattern:/\badvanced diploma\b/gi,explanation:'Specialised learning for advanced skilled or paraprofessional work.',check:'Check recognition for your intended role and any specific credit agreement.'},
 {id:'associate',label:'Associate Degree',level:'AQF 6',tone:'level6',pattern:/\bassociate degree\b/gi,explanation:'A qualification combining theoretical and practical learning. It shares a level with an Advanced Diploma, but is a different qualification type.',check:'Check course content and any written pathway into a Bachelor degree.'},
 {id:'honours',label:'Bachelor Honours',level:'AQF 8',tone:'level8',pattern:/\bbachelor(?:[’']s)?(?: degree)?(?: with)? honours(?: degree)?\b/gi,explanation:'Advanced study that may include a substantial research or professional project.',check:'Check the course structure and entry requirements; do not assume it is an automatic extra year.'},
 {id:'undergrad',label:'Undergraduate Certificate',level:'Covers AQF 5–7',tone:'crosslevel',pattern:/\bundergraduate certificate\b/gi,explanation:'A formal higher education qualification covering learning at AQF levels 5, 6 or 7, without one fixed AQF level.',check:'Check the actual subjects and credit arrangements for the course you want to enter.'},
 {id:'bachelor',label:'Bachelor Degree',level:'AQF 7',tone:'level7',pattern:/\bbachelor(?:[’']s)?(?: degree)?\b/giu,explanation:'Broad and coherent learning for professional work and further study.',check:'Check whether this specific course meets any professional recognition or registration requirements relevant to your goal.'},
 {id:'vocdegree',label:'Vocational Degree',level:'AQF 7',tone:'level7',pattern:/\bvocational degree\b/gi,explanation:'Degree-level vocational learning combining technical and theoretical knowledge for professional work.',check:'Check the actual accredited course, provider and admission requirements.'},
 {id:'masters',label:'Masters Degree',level:'AQF 9',tone:'level9',pattern:/\bmaster(?:s|[’']s)?(?: degree| of)\b/giu,explanation:'Advanced study through coursework, research or an extended professional program.',check:'Check which type it is, its entry requirements and whether it serves your intended goal.'},
 {id:'doctorate',label:'Doctoral Degree',level:'AQF 10',tone:'level10',pattern:/\b(?:doctorate|doctoral degree|ph\.?d\.?)\b/gi,explanation:'Study involving an original contribution to knowledge or professional practice.',check:'Check research fit, supervision, admission requirements and the commitment involved.'},
 ...(['I','II','III','IV'] as const).map((roman,index)=>({id:`cert${index+1}`,label:`Certificate ${roman}`,level:`AQF ${index+1}`,tone:`level${index+1}`,pattern:new RegExp(`\\b(?:certificate|cert\\.?)\\s*(?:${roman}|${index+1})\\b`,'gi'),explanation:['Builds foundational knowledge and skills for initial work and further learning. Check the actual subjects to understand what you will practise.','Builds introductory knowledge and practical skills for a defined area of work. A course may help you try basic tasks before further training; the actual subjects vary.','Combines theory and practical learning for skilled work. Look at which tasks you will practise and how the provider assesses them.','Builds broader, specialised knowledge and skills. Compare its subjects with your goal rather than assuming it is the next required step after Certificate III.'][index],check:'Check the named course and entry requirements. The level alone does not confirm job readiness, licensing or admission to the next course.'})),
 {id:'diploma',label:'Diploma',level:'AQF 5',tone:'level5',pattern:/\bdiploma\b/gi,explanation:'Technical and theoretical learning for advanced skilled or paraprofessional work.',check:'A Diploma can be offered in different education sectors. Check the actual course and any credit agreement instead of assuming progression.'},
 {id:'micro',label:'Microcredential',level:'Level varies',tone:'micro',pattern:/\bmicro[ -]?credentials?\b/gi,explanation:'A small credential focused on particular learning outcomes. Its recognition and relationship to a larger qualification vary.',check:'Check the issuer, assessment and written credit arrangements. Do not assume it stacks into a degree.'},
 {id:'short',label:'Short course',level:'Level not established',tone:'short',pattern:/\b(?:non[ -]accredited |non[ -]award )?short courses?\b/gi,explanation:'“Short” describes the learning format or length, not its accreditation or AQF level.',check:'Check whether this offering is accredited, what is assessed and what record you receive.'},
 {id:'industry',label:'Industry certification',level:'Check recognition',tone:'industry',pattern:/\b(?:industry|vendor)(?: \/ (?:industry|vendor))? certifications?\b/gi,explanation:'A credential associated with an industry, professional community or technology vendor.',check:'Check which employers value it, how skills are assessed and whether renewal is needed.'},
 {id:'unit',label:'VET unit / Skill Set',level:'Not a full qualification',tone:'unit',pattern:/\b(?:VET units?|skill sets?)\b/gi,explanation:'Targeted vocational learning can cover particular units without completing a whole qualification.',check:'Check unit codes, provider scope, the award issued and whether the units count towards your target qualification.'},
 {id:'apprentice',label:'Apprenticeship / traineeship',level:'Training route',tone:'route',pattern:/\b(?:apprenticeship|traineeship)\b/gi,explanation:'A route combining employment and training, rather than a separate qualification level.',check:'Check the linked qualification and employer arrangement. A suggested route is not an available job offer.'},
];
export function learningTypesIn(text:string):LearningType[]{
 const occupied:Array<[number,number]>=[],matches:Array<{start:number;type:LearningType}>=[];
 for(const {pattern,...type} of definitions){
  for(const match of text.matchAll(new RegExp(pattern.source,pattern.flags))){
   const start=match.index!,end=start+match[0].length;
   if(occupied.some(([a,b])=>start<b&&end>a))continue;
   // Pre-apprenticeship is not itself an apprenticeship or a confirmed AQF level.
   if(type.id==='apprentice'&&/pre[ -]$/i.test(text.slice(Math.max(0,start-4),start)))continue;
   occupied.push([start,end]);matches.push({start,type});
  }
 }
 const unique=new Map<string,LearningType>();
 for(const {type} of matches.sort((a,b)=>a.start-b.start))if(!unique.has(type.id))unique.set(type.id,type);
 return [...unique.values()];
}

export function learningToneIn(text:string):string|undefined{const found=learningTypesIn(text);const courses=found.filter(type=>type.tone!=="route");const types=courses.length?courses:found;return types.length===1?types[0].tone:undefined;}
