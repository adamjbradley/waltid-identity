'use strict';

/**
 * Server-side product catalogue.
 *
 * Mirrors the 12-entry `PRODUCT_CATALOGUE` in `public/index.html` so both
 * client (rendering the shelf) and server (validating cart adds) read from
 * the same source of truth. Every entry is flagged `ageRestricted: true` —
 * all current products are alcohol, and the age-gate logic in downstream
 * tasks shouldn't need to infer this from tags.
 */
const CATALOGUE = [
    { id: 'hibiki-harmony',  name: 'Hibiki Japanese Harmony',                     meta: 'Japan · Blended Japanese Whisky · 70cl · 43% ABV', icon: '\uD83E\uDD43', priceMix: 79.05,  priceSingle: 89.00,  was: 99.00,  ratingStars: 5, ratingCount: 412, minAge: 21, savePct: 10, tags: ['japanese', 'japanese whisky', 'whisky', 'blended'], ageRestricted: true },
    { id: 'yamazaki-12',     name: 'Yamazaki 12 Year Old Single Malt',            meta: 'Japan · Single Malt · 70cl · 43% ABV',              icon: '\uD83E\uDD43', priceMix: 175.50, priceSingle: 195.00, was: 219.00, ratingStars: 5, ratingCount: 288, minAge: 21, savePct: 11, tags: ['japanese', 'japanese whisky', 'single malt', 'whisky'], ageRestricted: true },
    { id: 'laphroaig-10',    name: 'Laphroaig 10 Year Old',                       meta: 'Islay, Scotland · Peated Single Malt · 70cl · 40% ABV', icon: '\uD83E\uDD43', priceMix: 44.55, priceSingle: 49.50, ratingStars: 5, ratingCount: 624, minAge: 21, tags: ['islay', 'islay scotch', 'scotch', 'single malt', 'peated', 'whisky'], ageRestricted: true },
    { id: 'ardbeg-10',       name: 'Ardbeg 10 Year Old',                          meta: 'Islay, Scotland · Peated Single Malt · 70cl · 46% ABV', icon: '\uD83E\uDD43', priceMix: 48.00, priceSingle: 53.00, ratingStars: 5, ratingCount: 504, minAge: 21, tags: ['islay', 'islay scotch', 'scotch', 'single malt', 'peated', 'whisky'], ageRestricted: true },
    { id: 'bookers-bourbon', name: "Booker's Bourbon Small Batch",                meta: 'Kentucky, USA · Bourbon · 75cl · 62.6% ABV',         icon: '\uD83E\uDD43', priceMix: 80.10, priceSingle: 89.00, was: 99.00, ratingStars: 5, ratingCount: 196, minAge: 21, savePct: 10, tags: ['bourbon', 'american whiskey', 'whisky'], ageRestricted: true },
    { id: 'maker-46',        name: "Maker's Mark 46",                             meta: 'Kentucky, USA · Bourbon · 70cl · 47% ABV',            icon: '\uD83E\uDD43', priceMix: 40.05, priceSingle: 44.50, ratingStars: 4, ratingCount: 332, minAge: 21, tags: ['bourbon', 'american whiskey', 'whisky'], ageRestricted: true },
    { id: 'redbreast-18',    name: 'Redbreast 18 Year Old Single Pot Still',      meta: 'Ireland · Single Pot Still Irish Whiskey · 70cl · 46% ABV', icon: '\uD83E\uDD47', priceMix: 160.65, priceSingle: 169.00, was: 189.00, ratingStars: 4, ratingCount: 87, minAge: 21, savePct: 15, tags: ['irish', 'irish whiskey', 'single pot still', 'whisky'], ageRestricted: true },
    { id: 'famous-grouse',   name: 'The Famous Grouse',                           meta: 'Scotland · Blended Scotch Whisky · 70cl · 40% ABV',   icon: '\uD83E\uDD43', priceMix: 18.00, priceSingle: 20.00, ratingStars: 3, ratingCount: 812, minAge: 21, tags: ['scotch', 'blended', 'blended whisky', 'whisky'], ageRestricted: true },
    { id: 'haigs-gold',      name: 'Haig Club Clubman',                           meta: 'Scotland · Single Grain Scotch · 70cl · 40% ABV',    icon: '\uD83E\uDD43', priceMix: 25.20, priceSingle: 28.00, ratingStars: 3, ratingCount: 241, minAge: 21, tags: ['scotch', 'grain whisky', 'single grain', 'whisky'], ageRestricted: true },
    { id: 'chateau-margaux', name: 'Château Margaux 2015',                        meta: 'Bordeaux, France · Red · 75cl',                       icon: '\uD83C\uDF77', priceMix: 224.25, priceSingle: 249.00, was: 299.00, ratingStars: 5, ratingCount: 142, minAge: 18, savePct: 25, tags: ['wine', 'red', 'bordeaux'], ageRestricted: true },
    { id: 'louis-roederer',  name: 'Louis Roederer Collection Brut NV',           meta: 'Champagne, France · Sparkling · 75cl',                icon: '\uD83C\uDF7E', priceMix: 38.25, priceSingle: 42.99, ratingStars: 5, ratingCount: 203, minAge: 18, tags: ['wine', 'champagne', 'sparkling'], ageRestricted: true },
    { id: 'tanqueray-ten',   name: 'Tanqueray No. Ten',                           meta: 'England · London Dry Gin · 70cl · 47.3% ABV',         icon: '\uD83C\uDF77', priceMix: 31.50, priceSingle: 35.00, ratingStars: 4, ratingCount: 278, minAge: 18, tags: ['gin'], ageRestricted: true },
];

const byId = Object.fromEntries(CATALOGUE.map(p => [p.id, p]));

function getProduct(id) {
    return byId[id] || null;
}

module.exports = { CATALOGUE, getProduct };
