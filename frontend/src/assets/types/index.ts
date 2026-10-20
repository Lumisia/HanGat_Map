export type CongestionLevel='QUIET'|'NORMAL'|'CROWDED'
export interface Place { congestionUnknown?: boolean }
/*
  통합(2026-08-17): 정동현님 data/data.ts 수정본이 아래 6개 필드를 추가했는데
  이 인터페이스에는 반영돼 오지 않아 type-check 가 깨졌다. 전부 optional 로 넣는다 —
  기존 데이터(4번 폴더 원본)에는 없는 필드라 required 로 두면 그쪽이 깨진다.
    regionCode / categoryCode  권역·분류 코드 (백엔드 enum 대응)
    imageUrl                   image 와 같은 값. 화면 두 곳이 이 이름으로 읽는다
    parkingAvailable / restroomAvailable  관광지 상세의 편의시설 줄
    goodPriceStore             착한가격업소 여부
*/
export interface Place{id:string;contentId?:string;canonicalName?:string;searchKeyword?:string;aliases?:string[];name:string;region:string;regionCode?:string;category:string;categoryCode?:string;address:string;latitude?:number;longitude?:number;description:string;score:number;pinLabel?:string;pinDescription?:string;level:CongestionLevel;time:string;stay:string;cost:string;image:string;imageUrl?:string;images?:string[];imageSource?:'TOUR_API'|'MOCK';parkingAvailable?:boolean;restroomAvailable?:boolean;goodPriceStore?:boolean;tags:string[]}
export interface TravelCondition{startDate:string;endDate:string;people:number;budget:number;regions:string[];transportation:string;styles:string[];preference:string}
