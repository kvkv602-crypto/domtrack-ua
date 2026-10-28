'use strict';

const {validateDate}=require('./fx.cjs');
const CURRENCIES=new Set(['UAH','USD','EUR']);
function fail(message){throw Object.assign(new Error(message),{statusCode:400});}
function priceBound(value,name){
  if(value===undefined||value===null||value==='')return null;
  if(typeof value!=='number'&&typeof value!=='string'||typeof value==='string'&&!/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(value))fail(`${name}: требуется неотрицательная цена.`);
  const number=Number(value);if(!Number.isFinite(number)||number<0||number>1e12)fail(`${name}: цена должна быть от 0 до 1000000000000.`);return number;
}
function planCurrency(filters={},defaultDate){
  const currency=filters.currency===undefined||filters.currency===null||filters.currency===''?null:filters.currency;
  if(currency!==null&&(typeof currency!=='string'||!CURRENCIES.has(currency)))fail('Выберите валюту отображения: USD, EUR или UAH.');
  const min=priceBound(filters.priceMin,'priceMin'),max=priceBound(filters.priceMax,'priceMax');
  if(min!==null&&max!==null&&min>max)fail('Минимальная цена больше максимальной.');
  const sort=filters.sort||'updated';
  if(!['updated','price-asc','price-desc','drop'].includes(sort))fail('Некорректная сортировка.');
  if(currency===null&&(min!==null||max!==null||sort==='price-asc'||sort==='price-desc'))fail('Для диапазона или сравнения цены выберите валюту отображения.');
  const date=validateDate(filters.fxDate===undefined||filters.fxDate===null||filters.fxDate===''?defaultDate:filters.fxDate);
  return {currency,date,min,max,sort,priceFilterApplied:min!==null||max!==null};
}
function rounded(value){return Math.round((value+Number.EPSILON)*100)/100;}
function rateFor(quote,from,to,date){
  if(from===to)return 1;
  if(!quote?.usable||quote.asOf!==date||quote.requestedDate!==date)return null;
  const source=quote.rates?.[from],target=quote.rates?.[to];
  return Number.isFinite(source)&&source>0&&Number.isFinite(target)&&target>0?source/target:null;
}
function annotateCurrency(item,plan,quote){
  const displayCurrency=plan.currency||item.currency;
  const rate=rateFor(quote,item.currency,displayCurrency,plan.date);
  const value=rate!==null?item.price*rate:null;
  const convertedPrice=Number.isFinite(value)?rounded(value):null;
  const rateUnavailable=convertedPrice===null;
  const convertedPricePerM2=convertedPrice!==null&&Number.isFinite(item.area)&&item.area>0?rounded(convertedPrice/item.area):null;
  return {...item,convertedPrice,displayCurrency,convertedPricePerM2,fxRate:rateUnavailable?null:rate,fxDate:!rateUnavailable&&item.currency!==displayCurrency?plan.date:null,rateUnavailable,priceRangeUnverified:plan.priceFilterApplied&&rateUnavailable};
}
function currencyRows(items,plan,quote,{includeUnverified=true}={}){
  let rateUnavailableCount=0,unverifiedPriceCount=0;
  const rows=[];
  for(const original of items){
    const item=annotateCurrency(original,plan,quote);
    if(item.rateUnavailable)rateUnavailableCount++;
    if(plan.priceFilterApplied){
      if(item.rateUnavailable){unverifiedPriceCount++;if(!includeUnverified)continue;}
      else if(plan.min!==null&&item.convertedPrice<plan.min||plan.max!==null&&item.convertedPrice>plan.max)continue;
    }
    rows.push(item);
  }
  if(plan.sort==='price-asc'||plan.sort==='price-desc')rows.sort((a,b)=>{
    if(a.convertedPrice===null&&b.convertedPrice!==null)return 1;
    if(a.convertedPrice!==null&&b.convertedPrice===null)return -1;
    const delta=a.convertedPrice===null?0:plan.sort==='price-asc'?a.convertedPrice-b.convertedPrice:b.convertedPrice-a.convertedPrice;
    return delta||String(b.lastSeen||'').localeCompare(String(a.lastSeen||''))||Number(a.id)-Number(b.id);
  });
  const warning=unverifiedPriceCount?`Для ${unverifiedPriceCount} объявлений нет курса НБУ на ${plan.date}: соответствие диапазону цены не проверено.`:rateUnavailableCount?`Для ${rateUnavailableCount} объявлений нет курса НБУ на ${plan.date}: показаны исходные цены без пересчёта.`:null;
  return {items:rows,currencyComparison:{currency:plan.currency,date:plan.date,rateUnavailableCount,unverifiedPriceCount,priceFilterApplied:plan.priceFilterApplied,warning,...(quote?.retryAt?{retryAt:quote.retryAt}:{})}};
}

module.exports={planCurrency,annotateCurrency,currencyRows};
