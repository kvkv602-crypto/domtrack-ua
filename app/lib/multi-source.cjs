'use strict';

async function runSources({sources,scan,progress,isClosed=()=>false,safeError=error=>String(error.message||error)}){
  const results=[];
  for(const source of sources){
    if(isClosed())break;
    progress.currentSource=source.name;
    try{results.push({source,result:await scan(source)});}
    catch(error){results.push({source,result:{recordsFound:0,recordsImported:0,pagesVisited:0,pagesAttempted:0,ingested:{received:0,inserted:0,updatedListings:0},errors:[{message:safeError(error)}],unsupportedPages:[],notices:[],outcome:'source-error',success:false}});}
    progress.completedSources++;
  }
  const combined={recordsFound:0,recordsImported:0,pagesVisited:0,pagesAttempted:0,ingested:{received:0,inserted:0,updatedListings:0,duplicates:0},errors:[],unsupportedPages:[],notices:[],pageResults:[],sources:[]};
  for(const {source,result}of results){
    for(const key of ['recordsFound','recordsImported','pagesVisited','pagesAttempted'])combined[key]+=Number(result[key])||0;
    for(const [key,value]of Object.entries(result.ingested||{}))if(typeof value==='number'&&Number.isFinite(value))combined.ingested[key]=(combined.ingested[key]||0)+value;
    for(const key of ['errors','unsupportedPages','notices','pageResults'])combined[key].push(...(result[key]||[]));
    combined.sources.push({id:source.id,name:source.name,url:source.url,outcome:result.outcome,success:!!result.success,recordsFound:result.recordsFound||0,recordsImported:result.recordsImported||0,pagesVisited:result.pagesVisited||0,errors:result.errors||[],unsupportedPages:result.unsupportedPages||[],pages:result.search?.pages||[],sections:result.search?.sections||[]});
  }
  const sections=combined.sources.flatMap(source=>source.sections),pages=combined.sources.flatMap(source=>source.pages);
  const failed=combined.sources.some(source=>source.outcome==='source-error');
  const hasMore=sections.some(section=>section.hasMore)||failed,retryable=sections.some(section=>section.retryable)||failed;
  const pending=sections.filter(section=>section.hasMore).map(section=>section.nextPage);
  const first=results[0]?.result.search||{};
  combined.search={...first,source:sources.length===1?sources[0].name:`${sources.length} площадки`,sourceIds:sources.map(source=>source.id),pagesPerType:2,pages,sections,firstPage:pages.length?Math.min(...pages.map(page=>page.page)):null,nextPages:results.length===1?(first.nextPages||{}):Object.fromEntries(sections.map(section=>[`${section.sourceId}:${section.kind}`,section.nextPage])),nextPage:pending.length?Math.min(...pending):null,hasMore,canLoadMore:hasMore,retryable,limitReached:sections.some(section=>section.limitReached)};
  combined.outcome=combined.recordsFound>0?'loaded':combined.sources.length&&combined.sources.every(source=>source.outcome==='complete')?'complete':combined.errors.length?'source-error':combined.unsupportedPages.length?'unsupported':'no-listings';
  combined.success=['loaded','complete'].includes(combined.outcome);
  combined.partial=combined.success&&combined.sources.some(source=>!source.success||source.errors.length||source.unsupportedPages.length);
  combined.coverage=`Проверены подключённые площадки: ${combined.sources.map(source=>source.name).join(', ')}. Загружается очередная порция страниц; полный рынок не охвачен. Результат каждого сайта показан отдельно.`;
  return combined;
}

module.exports={runSources};
