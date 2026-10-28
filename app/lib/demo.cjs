'use strict';
// Synthetic teaching data. These are not current listings or real addresses.
function demoRecords() {
  const items=[
    {externalId:'demo-kyiv-1',title:'Учебный пример · квартира у парка',city:'Київ',region:'Київська',kind:'apartment',rooms:2,area:63,floor:5,floorsTotal:9,prices:[89000,86000,83500],currency:'USD'},
    {externalId:'demo-lviv-1',title:'Учебный пример · квартира в центре',city:'Львів',region:'Львівська',kind:'apartment',rooms:3,area:82,floor:3,floorsTotal:5,prices:[118000,118000,121000],currency:'USD'},
    {externalId:'demo-odesa-1',title:'Учебный пример · однокомнатная квартира',city:'Одеса',region:'Одеська',kind:'apartment',rooms:1,area:38,floor:1,floorsTotal:10,prices:[45000,43500,43500],currency:'USD'},
    {externalId:'demo-dnipro-1',title:'Учебный пример · семейный дом',city:'Дніпро',region:'Дніпропетровська',kind:'house',rooms:5,area:165,floorsTotal:2,prices:[172000,169000,162000],currency:'USD'},
    {externalId:'demo-vinnytsia-1',title:'Учебный пример · квартира с террасой',city:'Вінниця',region:'Вінницька',kind:'apartment',rooms:2,area:70,floor:8,floorsTotal:8,prices:[3200000,3100000,3050000],currency:'UAH'},
    {externalId:'demo-irpin-1',title:'Учебный пример · дом с участком',city:'Ірпінь',region:'Київська',kind:'house',rooms:4,area:120,floorsTotal:1,prices:[99000,99000,99000],currency:'EUR'}
  ];
  return items.flatMap(({prices,...p})=>prices.map((price,i)=>({...p,source:'demo',deal:'sale',period:'total',price,status:'active',address:'Учебный адрес — не реальное предложение',observedAt:['2026-10-01T09:00:00.000Z','2026-10-10T09:00:00.000Z','2026-10-20T09:00:00.000Z'][i]})));
}
module.exports={demoRecords};
